package phonon.nodesmap

import org.bukkit.configuration.file.FileConfiguration

/** Immutable snapshot of config.yml. Rebuilt on /nodesmap reload. */
data class MapConfig(
    val world: String,
    val webEnabled: Boolean,
    val webBind: String,
    val webPort: Int,
    val webThreads: Int,
    val updateViewer: Boolean,
    val title: String,
    val liveUpdates: Boolean,
    val batchSeconds: Int,
    val snapshotBudgetNanos: Long,
    val fullRenderConcurrentLoads: Int,
    val zoomOutLevels: Int,
    val shading: Double,
    val waterDepth: Boolean,
    val chunkGrid: Boolean,
    val playersShow: Boolean,
    val playersUpdateTicks: Long,
    val hideSneaking: Boolean,
    val hideInvisible: Boolean,
    val hideSpectators: Boolean,
) {
    companion object {
        fun load(c: FileConfiguration): MapConfig = MapConfig(
            world = c.getString("world", "") ?: "",
            webEnabled = c.getBoolean("web.enabled", true),
            webBind = c.getString("web.bind", "0.0.0.0") ?: "0.0.0.0",
            webPort = c.getInt("web.port", 8100),
            webThreads = c.getInt("web.threads", 3).coerceIn(1, 16),
            updateViewer = c.getBoolean("web.update-viewer", true),
            title = c.getString("web.title", "Nodes Map") ?: "Nodes Map",
            liveUpdates = c.getBoolean("render.live-updates", true),
            batchSeconds = c.getInt("render.batch-seconds", 15).coerceIn(1, 3600),
            snapshotBudgetNanos = (c.getDouble("render.snapshot-budget-ms", 2.0).coerceIn(0.1, 40.0) * 1_000_000).toLong(),
            fullRenderConcurrentLoads = c.getInt("render.full-render-concurrent-loads", 12).coerceIn(1, 64),
            zoomOutLevels = c.getInt("render.zoom-out-levels", 5).coerceIn(0, 8),
            shading = c.getDouble("render.shading", 0.85).coerceIn(0.0, 1.0),
            waterDepth = c.getBoolean("render.water-depth", true),
            chunkGrid = c.getBoolean("render.chunk-grid", false),
            playersShow = c.getBoolean("players.show", true),
            playersUpdateTicks = c.getLong("players.update-ticks", 20L).coerceIn(5L, 1200L),
            hideSneaking = c.getBoolean("players.hide-sneaking", true),
            hideInvisible = c.getBoolean("players.hide-invisible", true),
            hideSpectators = c.getBoolean("players.hide-spectators", true),
        )
    }
}
