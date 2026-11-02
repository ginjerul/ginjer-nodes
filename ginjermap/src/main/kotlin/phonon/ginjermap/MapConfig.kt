package phonon.ginjermap

import org.bukkit.configuration.file.FileConfiguration

/** One area of the world owned by one server when the world is split across servers (Reef sharding), as a polygon outline. */
data class ShardRegion(val server: String, val xs: DoubleArray, val zs: DoubleArray)

/** Immutable snapshot of config.yml. Rebuilt on /ginjermap reload. */
data class MapConfig(
    val world: String,
    val webEnabled: Boolean,
    val webBind: String,
    val webPort: Int,
    val webThreads: Int,
    val updateViewer: Boolean,
    val title: String,
    val publicUrl: String,
    val editorEnabled: Boolean,
    val editorSessionMinutes: Int,
    val liveUpdates: Boolean,
    val batchSeconds: Int,
    val snapshotBudgetNanos: Long,
    val fullRenderConcurrentLoads: Int,
    val fullRenderThreads: Int,
    val zoomOutLevels: Int,
    val shading: Double,
    val waterDepth: Boolean,
    val chunkGrid: Boolean,
    val playersShow: Boolean,
    val playersUpdateTicks: Long,
    val hideSneaking: Boolean,
    val hideInvisible: Boolean,
    val hideSpectators: Boolean,
    val shards: List<ShardRegion>,
) {
    companion object {
        fun load(c: FileConfiguration): MapConfig = MapConfig(
            world = c.getString("world", "") ?: "",
            webEnabled = c.getBoolean("web.enabled", true),
            webBind = c.getString("web.bind", "0.0.0.0") ?: "0.0.0.0",
            webPort = c.getInt("web.port", 8100),
            webThreads = c.getInt("web.threads", 3).coerceIn(1, 16),
            updateViewer = c.getBoolean("web.update-viewer", true),
            title = c.getString("web.title", "Ginjer Map") ?: "Ginjer Map",
            publicUrl = (c.getString("web.public-url", "") ?: "").trim().trimEnd('/'),
            editorEnabled = c.getBoolean("editor.enabled", true),
            editorSessionMinutes = c.getInt("editor.session-minutes", 120).coerceIn(5, 7 * 24 * 60),
            liveUpdates = c.getBoolean("render.live-updates", true),
            batchSeconds = c.getInt("render.batch-seconds", 15).coerceIn(1, 3600),
            snapshotBudgetNanos = (c.getDouble("render.snapshot-budget-ms", 2.0).coerceIn(0.1, 40.0) * 1_000_000).toLong(),
            fullRenderConcurrentLoads = c.getInt("render.full-render-concurrent-loads", 24).coerceIn(1, 128),
            fullRenderThreads = c.getInt(
                "render.full-render-threads",
                (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
            ).coerceIn(1, 16),
            zoomOutLevels = c.getInt("render.zoom-out-levels", 5).coerceIn(0, 8),
            shading = c.getDouble("render.shading", 0.85).coerceIn(0.0, 1.0),
            waterDepth = c.getBoolean("render.water-depth", true),
            chunkGrid = c.getBoolean("render.chunk-grid", false),
            playersShow = c.getBoolean("players.show", true),
            playersUpdateTicks = c.getLong("players.update-ticks", 20L).coerceIn(5L, 1200L),
            hideSneaking = c.getBoolean("players.hide-sneaking", true),
            hideInvisible = c.getBoolean("players.hide-invisible", true),
            hideSpectators = c.getBoolean("players.hide-spectators", true),
            shards = if (c.getBoolean("shards.show", true)) loadShards(c) else emptyList(),
        )

        private fun loadShards(c: FileConfiguration): List<ShardRegion> {
            val out = ArrayList<ShardRegion>()
            for (m in c.getMapList("shards.regions")) {
                try {
                    out.add(parseShard(m))
                } catch (_: Exception) {
                    // skip malformed entries
                }
            }
            return out
        }

        private fun num(v: Any?): Double = when (v) {
            is Number -> v.toDouble()
            null -> throw IllegalArgumentException("missing number")
            else -> v.toString().toDouble()
        }

        /** Same shapes and maths as Reef's reef.yml regions: rectangle, polygon (optionally smooth), circle/ellipse. */
        fun parseShard(m: Map<*, *>): ShardRegion {
            val server = m["server"].toString()
            val pts = ArrayList<DoubleArray>()
            val points = m["points"]
            if (points is List<*>) {
                for (e in points) {
                    val pair = e as List<*>
                    pts.add(doubleArrayOf(num(pair[0]), num(pair[1])))
                }
                require(pts.size >= 3) { "polygon needs 3 points" }
                val shape = if (m["smooth"] == true) smooth(pts) else pts
                return ShardRegion(server, DoubleArray(shape.size) { shape[it][0] }, DoubleArray(shape.size) { shape[it][1] })
            }
            if (m.containsKey("center-x")) {
                val cx = num(m["center-x"]); val cz = num(m["center-z"])
                val rx = if (m.containsKey("radius-x")) num(m["radius-x"]) else num(m["radius"])
                val rz = if (m.containsKey("radius-z")) num(m["radius-z"]) else num(m["radius"])
                require(rx > 0 && rz > 0)
                val count = Math.ceil(2 * Math.PI * Math.max(rx, rz) / 8).coerceIn(32.0, 1024.0).toInt()
                return ShardRegion(
                    server,
                    DoubleArray(count) { cx + rx * Math.cos(2 * Math.PI * it / count) },
                    DoubleArray(count) { cz + rz * Math.sin(2 * Math.PI * it / count) },
                )
            }
            val minX = num(m["min-x"]); val minZ = num(m["min-z"]); val maxX = num(m["max-x"]); val maxZ = num(m["max-z"])
            require(minX < maxX && minZ < maxZ)
            return ShardRegion(server, doubleArrayOf(minX, maxX, maxX, minX), doubleArrayOf(minZ, minZ, maxZ, maxZ))
        }

        /** Closed Catmull-Rom spline through the points. */
        private fun smooth(p: List<DoubleArray>): List<DoubleArray> {
            val n = p.size
            val out = ArrayList<DoubleArray>()
            for (i in 0 until n) {
                val p0 = p[(i + n - 1) % n]; val p1 = p[i]; val p2 = p[(i + 1) % n]; val p3 = p[(i + 2) % n]
                val steps = Math.ceil(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 8).coerceIn(4.0, 256.0).toInt()
                for (s in 0 until steps) {
                    val t = s.toDouble() / steps
                    val t2 = t * t
                    val t3 = t2 * t
                    fun axis(k: Int) = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3)
                    out.add(doubleArrayOf(axis(0), axis(1)))
                }
            }
            return out
        }
    }
}
