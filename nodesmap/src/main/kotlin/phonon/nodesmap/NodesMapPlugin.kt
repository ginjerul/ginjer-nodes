package phonon.nodesmap

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
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
                web = WebServer(webRoot, cfg.webBind, cfg.webPort, cfg.webThreads, logger).also { it.start() }
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
            """"spawn":{"x":${spawn.blockX},"z":${spawn.blockZ}},"nodes":$nodes,""" +
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

    /** Copy the bundled viewer (resources/web/...) into plugins/NodesMap/web on version change. */
    private fun extractViewer() {
        val marker = webRoot.resolve(".viewer-version")
        val current = try {
            Files.readString(marker).trim()
        } catch (_: Exception) {
            ""
        }
        if (current == description.version && !description.version.endsWith("SNAPSHOT")) return
        if (current.isNotEmpty() && !cfg.updateViewer) return

        JarFile(file).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory || !entry.name.startsWith("web/")) continue
                val target = webRoot.resolve(entry.name.removePrefix("web/")).normalize()
                if (!target.startsWith(webRoot)) continue
                Files.createDirectories(target.parent)
                jar.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        Files.writeString(marker, description.version)
        logger.info("[NodesMap] viewer files installed (${description.version})")
    }

    // ------------------------------------------------------------------

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val s = scheduler ?: return true
        val r = renderer ?: return true
        val a = ChatColor.AQUA
        val g = ChatColor.GRAY
        when (args.getOrNull(0)?.lowercase()) {
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
                publishMapInfo()
                sender.sendMessage("$a[NodesMap]$g config reloaded (web server and world changes need a restart)")
            }
            else -> {
                sender.sendMessage("$a[NodesMap] commands:")
                sender.sendMessage("$g  /nodesmap status")
                sender.sendMessage("$g  /nodesmap fullrender [radiusChunks]${ChatColor.DARK_GRAY} - draw every generated chunk")
                sender.sendMessage("$g  /nodesmap render [radiusChunks]${ChatColor.DARK_GRAY} - redraw loaded chunks around you")
                sender.sendMessage("$g  /nodesmap cancel")
                sender.sendMessage("$g  /nodesmap reload")
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("status", "fullrender", "render", "cancel", "reload").filter { it.startsWith(args[0].lowercase()) }
        return emptyList()
    }
}
