package phonon.nodesmap

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import phonon.nodesmap.editor.EditorApi
import phonon.nodesmap.live.PlayerTracker
import phonon.nodesmap.render.BlockColors
import phonon.nodesmap.render.ChangeListener
import phonon.nodesmap.render.RenderScheduler
import phonon.nodesmap.render.RenderSettings
import phonon.nodesmap.render.TileRenderer
import phonon.nodesmap.web.WebServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.jar.JarFile

class NodesMapPlugin : JavaPlugin() {
    lateinit var cfg: MapConfig
        private set
    lateinit var webRoot: Path
        private set
    private lateinit var world: World

    private var renderer: TileRenderer? = null
    private var scheduler: RenderScheduler? = null
    private var listener: ChangeListener? = null
    private var web: WebServer? = null
    private var players: PlayerTracker? = null
    private var editor: EditorApi? = null

    /** recently redrawn tiles, so open browsers can swap just those images */
    private val tileUpdates = ConcurrentLinkedDeque<Pair<String, Long>>()

    override fun onEnable() {
        saveDefaultConfig()
        cfg = MapConfig.load(config)
        webRoot = dataFolder.toPath().resolve("web").toAbsolutePath().normalize()
        Files.createDirectories(webRoot.resolve("data").resolve("nodes"))

        world = (if (cfg.world.isNotEmpty()) Bukkit.getWorld(cfg.world) else null) ?: Bukkit.getWorlds()[0]

        extractViewer()
        BlockColors.init()

        val r = TileRenderer(webRoot.resolve("tiles").resolve(world.name), settingsOf(cfg), logger)
        r.onTileWritten = { level, x, z ->
            tileUpdates.addLast("$level/${x}_$z" to System.currentTimeMillis())
            while (tileUpdates.size > 4000) tileUpdates.pollFirst()
        }
        r.start()
        renderer = r

        val s = RenderScheduler(this, world, r)
        s.budgetNanos = cfg.snapshotBudgetNanos
        s.concurrentLoads = cfg.fullRenderConcurrentLoads
        s.readThreads = cfg.fullRenderThreads
        s.start(cfg.batchSeconds)
        scheduler = s

        if (cfg.liveUpdates) {
            listener = ChangeListener(s).also { server.pluginManager.registerEvents(it, this) }
        }

        if (cfg.webEnabled) {
            try {
                val ed = EditorApi(server, logger, cfg.editorSessionMinutes)
                editor = ed
                web = WebServer(webRoot, cfg.webBind, cfg.webPort, cfg.webThreads, logger).also {
                    it.api = { req ->
                        if (req.path.startsWith("/api/editor/") && cfg.editorEnabled) {
                            ed.handle(req)
                        } else {
                            WebServer.ApiResponse(404, "{\"error\":\"not found\"}")
                        }
                    }
                    it.start()
                }
            } catch (e: Exception) {
                logger.severe("could not start web server on ${cfg.webBind}:${cfg.webPort}: $e")
            }
        }
        publishMapInfo()

        players = PlayerTracker(
            this,
            world,
            { json -> web?.put("/data/players.json", json) },
            if (web == null) webRoot.resolve("data").resolve("players.json") else null,
        ).also { it.start(cfg) }

        server.scheduler.runTaskTimerAsynchronously(this, Runnable { publishTileUpdates() }, 100L, 100L)

        logger.info("rendering world '${world.name}'. Run /nodesmap fullrender once to draw the whole map.")
    }

    override fun onDisable() {
        players?.stop()
        scheduler?.stop()
        listener?.let { HandlerList.unregisterAll(it) }
        web?.stop()
        renderer?.shutdown()
    }

    private fun settingsOf(c: MapConfig) = RenderSettings(c.shading, c.waterDepth, c.chunkGrid, c.zoomOutLevels)

    /** /data/map.json: everything the viewer needs to set itself up */
    private fun publishMapInfo() {
        val spawn = world.spawnLocation
        val nodes = server.pluginManager.getPlugin("nodes") != null
        val json = """{"title":${jsonStr(cfg.title)},"world":${jsonStr(world.name)},""" +
            """"tileSize":${TileRenderer.TILE},"zoomOutLevels":${cfg.zoomOutLevels},""" +
            """"spawn":{"x":${spawn.blockX},"z":${spawn.blockZ}},"nodes":$nodes,"players":${cfg.playersShow},""" +
            """"version":${jsonStr(description.version)}}"""
        web?.put("/data/map.json", json)
        try {
            Files.writeString(webRoot.resolve("data").resolve("map.json"), json)
        } catch (_: Exception) {}
    }

    private fun publishTileUpdates() {
        val now = System.currentTimeMillis()
        val cutoff = now - 10 * 60_000L
        while (true) {
            val first = tileUpdates.peekFirst() ?: break
            if (first.second >= cutoff) break
            tileUpdates.pollFirst()
        }
        val sb = StringBuilder("{\"now\":").append(now)
        scheduler?.fullRenderProgress()?.let { p ->
            sb.append(",\"render\":{\"done\":").append(p.done).append(",\"total\":").append(p.total).append('}')
        }
        sb.append(",\"updates\":[")
        var first = true
        for ((k, t) in tileUpdates) {
            if (!first) sb.append(',')
            first = false
            sb.append("[\"").append(k).append("\",").append(t).append(']')
        }
        sb.append("]}")
        val json = sb.toString()
        web?.put("/data/tiles.json", json)
        if (web == null) {
            try {
                Files.writeString(webRoot.resolve("data").resolve("tiles.json"), json)
            } catch (_: Exception) {}
        }
    }

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Copy the bundled viewer (resources/web/...) into plugins/NodesMap/web.
     *
     * Files are refreshed whenever their contents in the jar change (a fingerprint
     * of every file's checksum), not just when the version number changes, and any
     * missing file is always restored. With web.update-viewer: false, existing files
     * are left alone (for customised viewers) but missing ones are still added.
     */
    private fun extractViewer() {
        val marker = webRoot.resolve(".viewer-version")
        val installed = try {
            Files.readString(marker).trim()
        } catch (_: Exception) {
            ""
        }

        JarFile(file).use { jar ->
            val entries = jar.entries().toList().filter { !it.isDirectory && it.name.startsWith("web/") }.sortedBy { it.name }
            val crc = java.util.zip.CRC32()
            for (e in entries) {
                crc.update(e.name.toByteArray())
                crc.update(e.crc.toString().toByteArray())
            }
            val fingerprint = "${description.version}-${crc.value.toString(36)}"
            val refreshAll = installed != fingerprint && (installed.isEmpty() || cfg.updateViewer)

            var written = 0
            for (entry in entries) {
                val target = webRoot.resolve(entry.name.removePrefix("web/")).normalize()
                if (!target.startsWith(webRoot)) continue
                if (!refreshAll && Files.exists(target)) continue
                Files.createDirectories(target.parent)
                jar.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                written++
            }
            if (refreshAll) Files.writeString(marker, fingerprint)
            if (written > 0) logger.info("installed $written viewer file(s) (${description.version})")
        }
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // messages: same look as Nodes' own commands (aqua text, white descriptions, red errors)

    private fun msg(sender: CommandSender, text: String) = sender.sendMessage("${ChatColor.AQUA}$text")

    private fun err(sender: CommandSender, text: String) = sender.sendMessage("${ChatColor.RED}$text")

    private fun line(sender: CommandSender, label: String, value: String) = msg(sender, "$label${ChatColor.WHITE}: $value")

    private fun mapUrl(): String = cfg.publicUrl.ifEmpty {
        val host = server.ip.ifEmpty { "localhost" }.let { if (it == "0.0.0.0") "localhost" else it }
        "http://$host:${cfg.webPort}"
    }

    private fun printHelp(sender: CommandSender) {
        msg(sender, "${ChatColor.BOLD}[Nodes] Map commands:")
        if (sender.hasPermission("nodesmap.link")) line(sender, "/nodesmap link", "Get a link to the web map")
        if (sender.hasPermission("nodesmap.editor")) line(sender, "/nodesmap editor", "Open the map editor (\"/nodesmap editor revoke\" to log out)")
        if (sender.hasPermission("nodesmap.admin")) {
            line(sender, "/nodesmap status", "Render queue, progress and web address")
            line(sender, "/nodesmap fullrender [radius]", "Draw every generated chunk (or within radius chunks of you)")
            line(sender, "/nodesmap render [radius]", "Redraw the loaded chunks around you")
            line(sender, "/nodesmap cancel", "Stop all pending renders")
            line(sender, "/nodesmap players [on|off]", "Show or hide players on the web map (now ${if (cfg.playersShow) "on" else "off"})")
            line(sender, "/nodesmap reload", "Reload config.yml")
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val s = scheduler ?: return true
        val r = renderer ?: return true
        val sub = args.getOrNull(0)?.lowercase() ?: "help"
        val adminOnly = setOf("status", "fullrender", "render", "cancel", "players", "reload")
        if (sub in adminOnly && !sender.hasPermission("nodesmap.admin")) {
            err(sender, "You don't have permission to use \"/nodesmap $sub\"")
            return true
        }
        when (sub) {
            "help" -> printHelp(sender)
            "link", "url" -> {
                if (!sender.hasPermission("nodesmap.link")) {
                    err(sender, "You don't have permission to use \"/nodesmap link\"")
                    return true
                }
                if (web == null && cfg.publicUrl.isEmpty()) {
                    err(sender, "[Nodes] The web map isn't running on this server")
                    return true
                }
                val url = mapUrl() + "/"
                if (sender is Player) {
                    sender.sendMessage(
                        Component.text("[Nodes] Web map: ", NamedTextColor.AQUA)
                            .append(Component.text(url, NamedTextColor.WHITE).clickEvent(ClickEvent.openUrl(url))),
                    )
                } else {
                    msg(sender, "[Nodes] Web map: ${ChatColor.WHITE}$url")
                }
            }
            "editor" -> editorCommand(sender, args)
            "status" -> {
                msg(sender, "${ChatColor.BOLD}[Nodes] Map status:")
                line(sender, "World", world.name)
                line(sender, "Web map", if (web != null) mapUrl() + "/" else "off (serving plugins/NodesMap/web yourself)")
                line(sender, "Players on map", if (cfg.playersShow) "shown" else "hidden")
                line(sender, "Full render", s.fullRenderProgress()?.let { progressText(it) } ?: "not running")
                line(sender, "Server loading", "${s.pendingCount} chunks (${s.dirtyCount} more changed, next batch)")
                line(sender, "Renderer", "${r.queued} queued, ${r.chunksRendered.get()} chunks drawn, ${r.tilesWritten.get()} tiles written")
            }
            "fullrender" -> {
                val radius = args.getOrNull(1)?.toIntOrNull()
                val p = sender as? Player
                val cx = if (radius != null && p != null) p.location.blockX shr 4 else null
                val cz = if (radius != null && p != null) p.location.blockZ shr 4 else null
                msg(sender, "[Nodes] Map: reading region files...")
                var reporter: org.bukkit.scheduler.BukkitTask? = null
                s.fullRender(
                    cx,
                    cz,
                    radius,
                    started = { n ->
                        if (n < 0) {
                            err(sender, "[Nodes] A full render is already running. Use \"/nodesmap status\" to watch it or \"/nodesmap cancel\" to stop it")
                        } else {
                            msg(sender, "[Nodes] Map: drawing ${ChatColor.WHITE}%,d${ChatColor.AQUA} chunks in the background, progress every 10s".format(n))
                            reporter = server.scheduler.runTaskTimer(
                                this,
                                Runnable {
                                    val pr = s.fullRenderProgress() ?: return@Runnable
                                    val text = "[Nodes] Map render ${progressText(pr)}"
                                    if (sender is Player) {
                                        if (sender.isOnline) sender.sendActionBar(Component.text(text, NamedTextColor.AQUA))
                                    } else {
                                        msg(sender, text)
                                    }
                                },
                                200L,
                                200L,
                            )
                        }
                    },
                    finished = { pr, cancelled ->
                        reporter?.cancel()
                        val secs = pr.elapsedMs / 1000
                        val diskDone = pr.done - pr.fallback - pr.reasons.filterKeys { it.startsWith("skipped") }.values.sum()
                        val head = if (cancelled) {
                            "[Nodes] Map render cancelled after %,d chunks".format(pr.done)
                        } else {
                            "[Nodes] Map: drew %,d chunks from disk in ${secs / 60}m ${secs % 60}s".format(diskDone)
                        }
                        val notify = { text: String ->
                            logger.info(text.removePrefix("[Nodes] "))
                            if (sender is Player && sender.isOnline) msg(sender, text)
                        }
                        notify(head)
                        for ((reason, n) in pr.reasons.entries.sortedByDescending { it.value }) {
                            notify("  %,d chunks ${reason}".format(n))
                        }
                        if (!cancelled && pr.fallback > 0) {
                            notify("[Nodes] Map: the server is now loading %,d chunks it couldn't read from disk. This is slower; progress every 10s".format(pr.fallback))
                            if (pr.reasons.keys.any { it.contains("1.18") || it.contains("old chunk format") }) {
                                notify("[Nodes] Tip: your world has chunks saved in a pre-1.18 format. Starting the server once with --forceUpgrade converts them all, so future renders are fast")
                            }
                            // follow the server-side loads until they're all drawn
                            var tracker: org.bukkit.scheduler.BukkitTask? = null
                            tracker = server.scheduler.runTaskTimer(
                                this,
                                Runnable {
                                    val left = s.pendingCount
                                    if (left == 0 && r.queued == 0) {
                                        tracker?.cancel()
                                        notify("[Nodes] Map render finished: the server loaded the remaining chunks")
                                    } else if (sender is Player && sender.isOnline) {
                                        sender.sendActionBar(Component.text("[Nodes] Map: server loading chunks, %,d left".format(left), NamedTextColor.AQUA))
                                    } else if (sender !is Player) {
                                        msg(sender, "[Nodes] Map: server loading chunks, %,d left".format(left))
                                    }
                                },
                                200L,
                                200L,
                            )
                        } else if (!cancelled) {
                            notify("[Nodes] Map render finished")
                        }
                    },
                )
            }
            "render" -> {
                val p = sender as? Player ?: run {
                    err(sender, "\"/nodesmap render\" must be run in game")
                    return true
                }
                val radius = (args.getOrNull(1)?.toIntOrNull() ?: 8).coerceIn(1, 64)
                val n = s.renderLoadedAround(p.location.blockX shr 4, p.location.blockZ shr 4, radius)
                msg(sender, "[Nodes] Map: redrawing ${ChatColor.WHITE}$n${ChatColor.AQUA} loaded chunks around you")
            }
            "players" -> {
                val show = when (args.getOrNull(1)?.lowercase()) {
                    "on", "show", "true" -> true
                    "off", "hide", "false" -> false
                    null -> !cfg.playersShow
                    else -> {
                        err(sender, "Usage: /nodesmap players [on|off]")
                        return true
                    }
                }
                config.set("players.show", show)
                saveConfig()
                cfg = MapConfig.load(config)
                players?.start(cfg)
                publishMapInfo()
                msg(sender, "[Nodes] Players are now ${ChatColor.WHITE}${if (show) "shown" else "hidden"}${ChatColor.AQUA} on the web map")
            }
            "cancel" -> {
                s.cancelAll()
                msg(sender, "[Nodes] Map: cancelled all pending renders")
            }
            "reload" -> {
                reloadConfig()
                cfg = MapConfig.load(config)
                r.settings = settingsOf(cfg)
                s.budgetNanos = cfg.snapshotBudgetNanos
                s.concurrentLoads = cfg.fullRenderConcurrentLoads
                s.readThreads = cfg.fullRenderThreads
                s.restartBatch(cfg.batchSeconds)
                players?.start(cfg)
                editor?.sessionMinutes = cfg.editorSessionMinutes
                editor?.resetBridge()
                publishMapInfo()
                msg(sender, "[Nodes] Reloaded map config (web server and world changes need a restart)")
            }
            else -> err(sender, "Invalid command, use \"/nodesmap help\"")
        }
        return true
    }

    private fun progressText(p: RenderScheduler.Progress): String {
        if (p.total <= 0) return "counting chunks..."
        val pct = p.done * 100 / p.total
        val rate = if (p.elapsedMs > 0) p.done * 1000L / p.elapsedMs else 0
        val left = if (rate > 0) (p.total - p.done) / rate else -1
        val eta = when {
            left < 0 -> ""
            left < 60 -> ", under a minute left"
            else -> ", about ${(left + 59) / 60} min left"
        }
        return "%d%% (%,d / %,d chunks, %,d/s%s)".format(pct, p.done, p.total, rate, eta)
    }

    private fun editorCommand(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("nodesmap.editor")) {
            err(sender, "You don't have permission to use \"/nodesmap editor\"")
            return
        }
        val ed = editor
        if (ed == null || !cfg.editorEnabled) {
            err(sender, "[Nodes] The map editor is turned off (needs web.enabled and editor.enabled in NodesMap's config)")
            return
        }
        if (args.getOrNull(1)?.lowercase() == "revoke") {
            val all = args.getOrNull(2)?.lowercase() == "all" && sender.hasPermission("nodesmap.admin")
            val n = ed.revoke(if (all) null else sender.name)
            msg(sender, "[Nodes] Ended ${ChatColor.WHITE}$n${ChatColor.AQUA} map editor session(s)")
            return
        }
        val token = ed.createSession(sender.name, (sender as? Player)?.uniqueId)
        val url = "${mapUrl()}/editor.html#token=$token"
        msg(sender, "[Nodes] Map editor link (valid ${cfg.editorSessionMinutes} minutes, don't share it):")
        if (sender is Player) {
            sender.sendMessage(
                Component.text("[Open map editor]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl(url))
                    .append(Component.text("  or click the link below to copy it", NamedTextColor.GRAY)),
            )
            sender.sendMessage(Component.text(url, NamedTextColor.GRAY).clickEvent(ClickEvent.copyToClipboard(url)))
        } else {
            sender.sendMessage(url)
        }
        line(sender, "/nodesmap editor revoke", "Log out of the editor early")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val opts = arrayListOf("help")
            if (sender.hasPermission("nodesmap.link")) opts += "link"
            if (sender.hasPermission("nodesmap.editor")) opts += "editor"
            if (sender.hasPermission("nodesmap.admin")) opts += listOf("status", "fullrender", "render", "cancel", "players", "reload")
            return opts.filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("editor", true)) return listOf("revoke").filter { it.startsWith(args[1].lowercase()) }
        if (args.size == 2 && args[0].equals("players", true)) return listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
        return emptyList()
    }
}
