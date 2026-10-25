package phonon.nodesmap.render

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.HeightMap
import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main-thread side of rendering.
 *
 *  - Changed chunks are collected into a set (cheap) and released in batches.
 *  - Each tick, chunk snapshots are taken until the time budget is used up.
 *    Snapshots are handed to the TileRenderer thread; nothing else runs here.
 *  - Full renders read region files directly on background threads (see
 *    RegionReader), so the server never loads chunks for them. Only chunks the
 *    reader can't handle (pre-1.18 format, LZ4) fall back to Paper's async
 *    chunk loading, and chunks are never generated.
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

    var readThreads: Int = 2

    // full render progress (written by reader threads)
    class Progress(val done: Int, val total: Int, val fallback: Int, val elapsedMs: Long)

    private val frTotal = AtomicInteger(0)
    private val frDone = AtomicInteger(0)
    private val frFallback = AtomicInteger(0)
    private var frStarted = 0L

    @Volatile private var frCancel = false

    @Volatile var fullRenderActive = false
        private set

    fun fullRenderProgress(): Progress? = if (!fullRenderActive) {
        null
    } else {
        Progress(frDone.get(), frTotal.get(), frFallback.get(), System.currentTimeMillis() - frStarted)
    }

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
        frCancel = true
        pending.clear()
        pendingSet.clear()
        dirty.clear()
        renderer.clearQueue()
    }

    // ------------------------------------------------------------------

    private fun tick() {
        if (pending.isEmpty()) return
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
        renderer.submit(ChunkJob(cx, cz, SnapshotSource(snap), world.minHeight, world.maxHeight, north))
    }

    // ------------------------------------------------------------------
    // full render

    /**
     * Draw every generated chunk (optionally only within [radius] chunks of a center).
     *
     * Region files are read on [readThreads] background threads and handed
     * straight to the renderer; the main thread only receives the chunks the
     * reader couldn't decode. [started] gets the chunk count (-1 if a full render
     * is already running) and [finished] runs on the main thread at the end.
     */
    fun fullRender(
        centerCx: Int? = null,
        centerCz: Int? = null,
        radius: Int? = null,
        started: (Int) -> Unit,
        finished: (Progress, Boolean) -> Unit,
    ) {
        if (fullRenderActive) {
            started(-1)
            return
        }
        fullRenderActive = true
        frCancel = false
        frTotal.set(0)
        frDone.set(0)
        frFallback.set(0)
        frStarted = System.currentTimeMillis()

        val dir = regionFolder(world)
        val minY = world.minHeight
        val maxY = world.maxHeight
        val reader = RegionReader(minY, maxY)
        val want: (Int, Int) -> Boolean = if (radius != null && centerCx != null && centerCz != null) {
            { cx, cz -> Math.abs(cx - centerCx) <= radius && Math.abs(cz - centerCz) <= radius }
        } else {
            { _, _ -> true }
        }
        val threads = readThreads

        val main = Thread({
            val regions = listRegions(dir).filter { (rx, rz, _) ->
                radius == null || centerCx == null || centerCz == null ||
                    (rx * 32 + 31 >= centerCx - radius && rx * 32 <= centerCx + radius && rz * 32 + 31 >= centerCz - radius && rz * 32 <= centerCz + radius)
            }
            var total = 0
            for ((rx, rz, path) in regions) {
                total += reader.listChunks(path, rx, rz).count { (cx, cz) -> want(cx, cz) }
            }
            frTotal.set(total)
            Bukkit.getScheduler().runTask(plugin, Runnable { started(total) })

            val fallbacks = ConcurrentLinkedQueue<Long>()
            val pool = Executors.newFixedThreadPool(threads) { r ->
                Thread(r, "NodesMap-Read").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY + 1
                }
            }
            val futures = regions.map { (rx, rz, path) ->
                pool.submit {
                    reader.readRegion(path, rx, rz, { frCancel }, want) { cx, cz, result ->
                        when (result) {
                            is RegionReader.Result.Ok -> {
                                // backpressure: keep memory bounded while the renderer catches up
                                while (renderer.queued > 512 && !frCancel) Thread.sleep(4)
                                renderer.submit(ChunkJob(cx, cz, result.chunk, minY, maxY, null))
                            }
                            is RegionReader.Result.Fallback -> {
                                fallbacks.add(TileRenderer.key(cx, cz))
                                frFallback.incrementAndGet()
                            }
                            is RegionReader.Result.Skip -> {}
                        }
                        frDone.incrementAndGet()
                    }
                }
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: Exception) {
                    plugin.logger.warning("[NodesMap] reading a region failed: ${e.cause ?: e}")
                }
            }
            pool.shutdown()

            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    val cancelled = frCancel
                    if (!cancelled) {
                        // chunks the reader couldn't decode, plus loaded chunks whose
                        // newest changes may not be saved to disk yet
                        for (k in fallbacks) enqueue(k)
                        for (c in world.loadedChunks) {
                            if (want(c.x, c.z)) enqueue(TileRenderer.key(c.x, c.z))
                        }
                    }
                    val p = Progress(frDone.get(), frTotal.get(), frFallback.get(), System.currentTimeMillis() - frStarted)
                    fullRenderActive = false
                    finished(p, cancelled)
                },
            )
        }, "NodesMap-FullRender")
        main.isDaemon = true
        main.start()
    }

    private fun listRegions(dir: Path): List<Triple<Int, Int, Path>> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().matches(Regex("""r\.-?\d+\.-?\d+\.mca""")) }.toList()
        }
        // north to south, so hill shading usually has the chunk above it already drawn
        return files.mapNotNull { p ->
            val parts = p.fileName.toString().split('.')
            val rx = parts[1].toIntOrNull() ?: return@mapNotNull null
            val rz = parts[2].toIntOrNull() ?: return@mapNotNull null
            Triple(rx, rz, p)
        }.sortedWith(compareBy({ it.second }, { it.first }))
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
}
