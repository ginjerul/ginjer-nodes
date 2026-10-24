package phonon.nodesmap.render

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.HeightMap
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Main-thread side of rendering.
 *
 *  - Changed chunks are collected into a set (cheap) and released in batches.
 *  - Each tick, chunk snapshots are taken until the time budget is used up.
 *    Snapshots are handed to the TileRenderer thread; nothing else runs here.
 *  - Unloaded chunks (full render, or a chunk that unloaded before its batch)
 *    are loaded with Paper's async chunk loading and never generated.
 */
class RenderScheduler(
    private val plugin: Plugin,
    val world: World,
    private val renderer: TileRenderer,
) {
    private val dirty: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val pending = ArrayDeque<Long>()
    private val pendingSet = HashSet<Long>()
    private var inFlight = 0

    var budgetNanos: Long = 2_000_000
    var concurrentLoads: Int = 12

    var fullRenderTotal = 0
        private set
    var fullRenderActive = false
        private set

    private var tickTask: BukkitTask? = null
    private var batchTask: BukkitTask? = null

    fun start(batchSeconds: Int) {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        restartBatch(batchSeconds)
    }

    fun restartBatch(batchSeconds: Int) {
        batchTask?.cancel()
        val period = batchSeconds * 20L
        batchTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { releaseDirty() }, period, period)
    }

    fun stop() {
        tickTask?.cancel()
        batchTask?.cancel()
    }

    val pendingCount: Int get() = pending.size + inFlight
    val dirtyCount: Int get() = dirty.size

    /** Called from listeners when a surface block changes. */
    fun markDirty(cx: Int, cz: Int) {
        dirty.add(TileRenderer.key(cx, cz))
    }

    private fun releaseDirty() {
        if (dirty.isEmpty()) return
        val it = dirty.iterator()
        while (it.hasNext()) {
            val k = it.next()
            it.remove()
            enqueue(k)
        }
    }

    private fun enqueue(k: Long) {
        if (pendingSet.add(k)) pending.addLast(k)
    }

    fun cancelAll() {
        pending.clear()
        pendingSet.clear()
        dirty.clear()
        renderer.clearQueue()
        fullRenderActive = false
    }

    // ------------------------------------------------------------------

    private fun tick() {
        if (pending.isEmpty()) {
            if (fullRenderActive && inFlight == 0) {
                fullRenderActive = false
                plugin.logger.info("[NodesMap] full render finished: $fullRenderTotal chunks queued for drawing")
            }
            return
        }
        // keep the render thread from falling too far behind (memory)
        if (renderer.queued > 2_000) return

        val start = System.nanoTime()
        while (pending.isNotEmpty() && System.nanoTime() - start < budgetNanos) {
            val k = pending.first()
            val cx = TileRenderer.keyX(k)
            val cz = TileRenderer.keyZ(k)
            if (world.isChunkLoaded(cx, cz)) {
                pending.removeFirst()
                pendingSet.remove(k)
                snapshot(world.getChunkAt(cx, cz))
            } else {
                if (inFlight >= concurrentLoads) break
                pending.removeFirst()
                pendingSet.remove(k)
                inFlight++
                world.getChunkAtAsync(cx, cz, false).whenComplete { chunk, _ ->
                    // Paper completes this on the main thread; hop there just in case
                    if (Bukkit.isPrimaryThread()) {
                        onLoaded(chunk)
                    } else {
                        Bukkit.getScheduler().runTask(plugin, Runnable { onLoaded(chunk) })
                    }
                }
            }
        }
    }

    private fun onLoaded(chunk: Chunk?) {
        inFlight--
        if (chunk != null) snapshot(chunk)
    }

    private fun snapshot(chunk: Chunk) {
        val snap = chunk.getChunkSnapshot(true, false, false)
        val cx = chunk.x
        val cz = chunk.z
        val north: IntArray? = if (world.isChunkLoaded(cx, cz - 1)) {
            IntArray(16) { world.getHighestBlockYAt(cx * 16 + it, cz * 16 - 1, HeightMap.WORLD_SURFACE) }
        } else {
            null
        }
        renderer.submit(ChunkJob(cx, cz, snap, world.minHeight, world.maxHeight, north))
    }

    // ------------------------------------------------------------------
    // full render

    /**
     * Queue every generated chunk (optionally only within radius chunks of a center).
     * Region headers are scanned off the main thread.
     */
    fun fullRender(centerCx: Int? = null, centerCz: Int? = null, radius: Int? = null, done: (Int) -> Unit) {
        val regionDir = regionFolder(world)
        Bukkit.getScheduler().runTaskAsynchronously(
            plugin,
            Runnable {
                val keys = scanRegions(regionDir, centerCx, centerCz, radius)
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        for (k in keys) enqueue(k)
                        fullRenderTotal = keys.size
                        fullRenderActive = true
                        done(keys.size)
                    },
                )
            },
        )
    }

    /** Re-render the chunks around a point that are already loaded (fast, no IO). */
    fun renderLoadedAround(cx: Int, cz: Int, radius: Int): Int {
        var n = 0
        for (z in cz - radius..cz + radius) {
            for (x in cx - radius..cx + radius) {
                if (world.isChunkLoaded(x, z)) {
                    enqueue(TileRenderer.key(x, z))
                    n++
                }
            }
        }
        return n
    }

    private fun regionFolder(w: World): Path {
        val base = w.worldFolder.toPath()
        return when (w.environment) {
            World.Environment.NETHER -> base.resolve("DIM-1").resolve("region")
            World.Environment.THE_END -> base.resolve("DIM1").resolve("region")
            else -> base.resolve("region")
        }
    }

    private fun scanRegions(dir: Path, ccx: Int?, ccz: Int?, radius: Int?): List<Long> {
        if (!Files.isDirectory(dir)) return emptyList()
        val regionFiles = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().matches(Regex("""r\.-?\d+\.-?\d+\.mca""")) }.toList()
        }
        val out = ArrayList<Long>()
        // render in region order, rows north->south so hill shading has its neighbor
        val sorted = regionFiles.mapNotNull { p ->
            val parts = p.fileName.toString().split('.')
            val rx = parts[1].toIntOrNull() ?: return@mapNotNull null
            val rz = parts[2].toIntOrNull() ?: return@mapNotNull null
            Triple(rx, rz, p)
        }.sortedWith(compareBy({ it.second }, { it.first }))

        for ((rx, rz, path) in sorted) {
            val header = try {
                DataInputStream(Files.newInputStream(path).buffered()).use { dis ->
                    IntArray(1024) { dis.readInt() }
                }
            } catch (_: Exception) {
                continue
            }
            for (lz in 0 until 32) {
                for (lx in 0 until 32) {
                    if (header[lz * 32 + lx] == 0) continue
                    val cx = rx * 32 + lx
                    val cz = rz * 32 + lz
                    if (radius != null && ccx != null && ccz != null) {
                        if (Math.abs(cx - ccx) > radius || Math.abs(cz - ccz) > radius) continue
                    }
                    out.add(TileRenderer.key(cx, cz))
                }
            }
        }
        return out
    }
}
