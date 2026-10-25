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
                logger.severe("[NodesMap] could not start web server on ${cfg.webBind}:${cfg.webPort}: $e")
            }
        }
        publishMapInfo()

        players = PlayerTracker(
            this,
            world,
            { json -> web?.put("/data/players.json", json) },
            if (web == null) webRoot.resolve("data").resolve("players.json") else null,
        ).also { it.start(cfg) }

        server.scheduler.runTaskTimerAsynchronously(this, Runnable { publishTileUpdates() }, 100L, 200L)

        logger.info("[NodesMap] rendering world '${world.name}'. Run /nodesmap fullrender once to draw the whole map.")
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
        val sb = StringBuilder("{\"now\":").append(now).append(",\"updates\":[")
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
            if (written > 0) logger.info("[NodesMap] installed $written viewer file(s) (${description.version})")
        }
    }

    // ------------------------------------------------------------------

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val s = scheduler ?: return true
        val r = renderer ?: return true
        val a = ChatColor.AQUA
        val g = ChatColor.GRAY
        val sub = args.getOrNull(0)?.lowercase()
        if (sub == "editor") {
            editorCommand(sender, args)
            return true
        }
        if (!sender.hasPermission("nodesmap.admin")) {
            if (sender.hasPermission("nodesmap.editor")) {
                sender.sendMessage("$a[NodesMap]$g /nodesmap editor${ChatColor.DARK_GRAY} - open the map editor")
            } else {
                sender.sendMessage("${ChatColor.RED}You don't have permission to use this command.")
            }
            return true
        }
        when (sub) {
            "status" -> {
                sender.sendMessage("$a[NodesMap]$g world: ${ChatColor.WHITE}${world.name}")
                sender.sendMessage("$g  waiting to snapshot: ${ChatColor.WHITE}${s.pendingCount}$g, changed (next batch): ${ChatColor.WHITE}${s.dirtyCount}")
                sender.sendMessage("$g  render queue: ${ChatColor.WHITE}${r.queued}$g, chunks drawn: ${ChatColor.WHITE}${r.chunksRendered.get()}$g, tiles written: ${ChatColor.WHITE}${r.tilesWritten.get()}")
                if (s.fullRenderActive) sender.sendMessage("$g  full render: ${ChatColor.WHITE}${s.fullRenderTotal - s.pendingCount}/${s.fullRenderTotal}")
                sender.sendMessage("$g  web: ${ChatColor.WHITE}${if (web != null) "http://${cfg.webBind}:${cfg.webPort}/" else "off (serve plugins/NodesMap/web yourself)"}")
            }
            "fullrender" -> {
                val radius = args.getOrNull(1)?.toIntOrNull()
                val p = sender as? Player
                val cx = if (radius != null && p != null) p.location.blockX shr 4 else null
                val cz = if (radius != null && p != null) p.location.blockZ shr 4 else null
                sender.sendMessage("$a[NodesMap]$g scanning region files...")
                s.fullRender(cx, cz, radius) { n ->
                    sender.sendMessage("$a[NodesMap]$g queued ${ChatColor.WHITE}$n$g chunks. Check progress with /nodesmap status")
                }
            }
            "render" -> {
                val p = sender as? Player ?: run {
                    sender.sendMessage("$g in-game only")
                    return true
                }
                val radius = (args.getOrNull(1)?.toIntOrNull() ?: 8).coerceIn(1, 64)
                val n = s.renderLoadedAround(p.location.blockX shr 4, p.location.blockZ shr 4, radius)
                sender.sendMessage("$a[NodesMap]$g queued ${ChatColor.WHITE}$n$g loaded chunks around you")
            }
            "players" -> {
                val arg = args.getOrNull(1)?.lowercase()
                val show = when (arg) {
                    "on", "show", "true" -> true
                    "off", "hide", "false" -> false
                    null -> !cfg.playersShow
                    else -> {
                        sender.sendMessage("$g usage: /nodesmap players [on|off]")
                        return true
                    }
                }
                config.set("players.show", show)
                saveConfig()
                cfg = MapConfig.load(config)
                players?.start(cfg)
                publishMapInfo()
                sender.sendMessage(
                    "$a[NodesMap]$g players are now ${if (show) "${ChatColor.GREEN}shown" else "${ChatColor.RED}hidden"}$g on the web map" +
                        if (show) "" else " (open maps clear them within a few seconds)",
                )
            }
            "cancel" -> {
                s.cancelAll()
                sender.sendMessage("$a[NodesMap]$g cancelled all pending renders")
            }
            "reload" -> {
                reloadConfig()
                cfg = MapConfig.load(config)
                r.settings = settingsOf(cfg)
                s.budgetNanos = cfg.snapshotBudgetNanos
                s.concurrentLoads = cfg.fullRenderConcurrentLoads
                s.restartBatch(cfg.batchSeconds)
                players?.start(cfg)
                editor?.sessionMinutes = cfg.editorSessionMinutes
                editor?.resetBridge()
                publishMapInfo()
                sender.sendMessage("$a[NodesMap]$g config reloaded (web server and world changes need a restart)")
            }
            else -> {
                sender.sendMessage("$a[NodesMap] commands:")
                sender.sendMessage("$g  /nodesmap status")
                sender.sendMessage("$g  /nodesmap fullrender [radiusChunks]${ChatColor.DARK_GRAY} - draw every generated chunk")
                sender.sendMessage("$g  /nodesmap render [radiusChunks]${ChatColor.DARK_GRAY} - redraw loaded chunks around you")
                sender.sendMessage("$g  /nodesmap players [on|off]${ChatColor.DARK_GRAY} - show or hide players on the web map (now ${if (cfg.playersShow) "on" else "off"})")
                sender.sendMessage("$g  /nodesmap cancel")
                sender.sendMessage("$g  /nodesmap reload")
                sender.sendMessage("$g  /nodesmap editor [revoke]${ChatColor.DARK_GRAY} - get a login link for the map editor")
            }
        }
        return true
    }

    private fun editorCommand(sender: CommandSender, args: Array<out String>) {
        val a = ChatColor.AQUA
        val g = ChatColor.GRAY
        if (!sender.hasPermission("nodesmap.editor")) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to use the map editor.")
            return
        }
        val ed = editor
        if (ed == null || !cfg.editorEnabled) {
            sender.sendMessage("$a[NodesMap]$g the editor is off (needs web.enabled and editor.enabled)")
            return
        }
        if (args.getOrNull(1)?.lowercase() == "revoke") {
            val all = args.getOrNull(2)?.lowercase() == "all" && sender.hasPermission("nodesmap.admin")
            val n = ed.revoke(if (all) null else sender.name)
            sender.sendMessage("$a[NodesMap]$g ended $n editor session(s)")
            return
        }
        val token = ed.createSession(sender.name, (sender as? Player)?.uniqueId)
        val base = cfg.publicUrl.ifEmpty {
            val host = server.ip.ifEmpty { "localhost" }.let { if (it == "0.0.0.0") "localhost" else it }
            "http://$host:${cfg.webPort}"
        }
        val url = "$base/editor.html#token=$token"
        sender.sendMessage("$a[NodesMap]$g editor link (valid ${cfg.editorSessionMinutes} min, don't share it):")
        if (sender is Player) {
            sender.sendMessage(
                Component.text("  [Open map editor]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.openUrl(url))
                    .append(Component.text("  click, or copy below", NamedTextColor.DARK_GRAY)),
            )
            sender.sendMessage(Component.text(url, NamedTextColor.GRAY).clickEvent(ClickEvent.copyToClipboard(url)))
        } else {
            sender.sendMessage(url)
        }
        sender.sendMessage("$g  end it early with /nodesmap editor revoke")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val opts = ArrayList<String>()
            if (sender.hasPermission("nodesmap.admin")) opts += listOf("status", "fullrender", "render", "players", "cancel", "reload")
            if (sender.hasPermission("nodesmap.editor")) opts += "editor"
            return opts.filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("editor", true)) return listOf("revoke").filter { it.startsWith(args[1].lowercase()) }
        if (args.size == 2 && args[0].equals("players", true)) return listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
        return emptyList()
    }
}
