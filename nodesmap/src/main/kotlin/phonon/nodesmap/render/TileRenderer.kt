package phonon.nodesmap.render

import org.bukkit.Material
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.imageio.ImageIO

/** One chunk ready to render. Created on the main thread, consumed on the render thread. */
class ChunkJob(
    val cx: Int,
    val cz: Int,
    val source: ColumnSource,
    val minY: Int,
    /** exclusive */
    val maxY: Int,
    /** heights of the 16 blocks just north of this chunk, or null if unknown */
    val northEdge: IntArray?,
)

data class RenderSettings(
    val shading: Double,
    val waterDepth: Boolean,
    val chunkGrid: Boolean,
    val zoomOutLevels: Int,
)

/**
 * Tile layout (Leaflet-compatible, see web/app.js):
 *   tiles/<world>/0/<rx>_<rz>.png   one 512x512 tile per region (1 px = 1 block)
 *   tiles/<world>/k/<x>_<z>.png     zoomed out: each tile covers 512 * 2^k blocks
 *
 * All image work happens on ONE background thread that owns every buffer, so
 * there is no locking and the server's main thread never touches pixels.
 */
class TileRenderer(
    private val tilesDir: Path,
    @Volatile var settings: RenderSettings,
    private val logger: Logger,
) {
    companion object {
        const val TILE = 512
        private const val REGION_CACHE_SIZE = 12
        private const val FLUSH_INTERVAL_MS = 4_000L
        private const val PYRAMID_INTERVAL_MS = 12_000L
        private const val UNKNOWN: Short = Short.MIN_VALUE

        fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        fun keyX(k: Long): Int = (k shr 32).toInt()
        fun keyZ(k: Long): Int = k.toInt()
    }

    private class Region(val rx: Int, val rz: Int, val pixels: IntArray) {
        val heights = ShortArray(TILE * TILE) { UNKNOWN }
        var dirty = false
    }

    private val queue = LinkedBlockingQueue<ChunkJob>()
    private val regions = LinkedHashMap<Long, Region>(16, 0.75f, true)
    private val dirtyPyramid: Array<HashSet<Long>> = Array(9) { HashSet() }

    /** called on the render thread after a tile file is replaced: (level, x, z) */
    @Volatile var onTileWritten: ((Int, Int, Int) -> Unit)? = null

    val chunksRendered = AtomicLong(0)
    val tilesWritten = AtomicLong(0)
    val queued: Int get() = queue.size

    @Volatile private var running = true
    private val thread = Thread(::loop, "NodesMap-Render").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY + 1
    }

    fun start() = thread.start()

    fun submit(job: ChunkJob) {
        queue.add(job)
    }

    fun clearQueue() = queue.clear()

    /** Stops the thread after flushing what's already rendered. */
    fun shutdown() {
        running = false
        thread.interrupt()
        thread.join(10_000)
    }

    // ------------------------------------------------------------------

    private fun loop() {
        var lastFlush = System.currentTimeMillis()
        var lastPyramid = lastFlush
        while (running) {
            try {
                val job = queue.poll(500, TimeUnit.MILLISECONDS)
                if (job != null) {
                    renderChunk(job)
                    chunksRendered.incrementAndGet()
                }
                val now = System.currentTimeMillis()
                val idle = queue.isEmpty()
                if (now - lastFlush > FLUSH_INTERVAL_MS || (idle && job == null)) {
                    flushRegions()
                    lastFlush = now
                }
                if (now - lastPyramid > PYRAMID_INTERVAL_MS || (idle && job == null)) {
                    buildPyramid()
                    lastPyramid = now
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Throwable) {
                logger.warning("[NodesMap] render error: $e")
            }
        }
        try {
            flushRegions()
            buildPyramid()
        } catch (e: Throwable) {
            logger.warning("[NodesMap] final flush failed: $e")
        }
    }

    // ------------------------------------------------------------------
    // chunk -> pixels

    private fun renderChunk(job: ChunkJob) {
        val rx = Math.floorDiv(job.cx, 32)
        val rz = Math.floorDiv(job.cz, 32)
        val region = region(rx, rz)
        val ox = Math.floorMod(job.cx, 32) * 16
        val oz = Math.floorMod(job.cz, 32) * 16
        val s = settings
        val snap = job.source

        for (z in 0 until 16) {
            for (x in 0 until 16) {
                val idx = (oz + z) * TILE + (ox + x)
                var y = snap.topY(x, z).coerceAtMost(job.maxY - 1)
                var color = BlockColors.TRANSPARENT
                var mat: Material = Material.AIR
                while (y >= job.minY) {
                    mat = snap.type(x, y, z)
                    color = BlockColors.color(mat)
                    if (color != BlockColors.TRANSPARENT) break
                    y--
                }
                if (color == BlockColors.TRANSPARENT) {
                    region.pixels[idx] = 0
                    region.heights[idx] = UNKNOWN
                    continue
                }

                var argb: Int
                if (BlockColors.isWater(mat)) {
                    // measure depth and peek at the floor
                    var d = 0
                    var fy = y
                    var floor = color
                    while (fy > job.minY) {
                        val m = snap.type(x, fy, z)
                        if (!BlockColors.isWater(m)) {
                            val c = BlockColors.color(m)
                            if (c != BlockColors.TRANSPARENT) {
                                floor = c
                                break
                            }
                        }
                        d++
                        fy--
                        if (d > 40) break
                    }
                    argb = if (s.waterDepth) {
                        val shallowMix = if (d <= 2) 0.45 else if (d <= 5) 0.2 else 0.0
                        val darken = (1.0 - d * 0.018).coerceIn(0.62, 1.0)
                        scale(mix(color, floor, shallowMix), darken)
                    } else {
                        color
                    }
                    // water is flat: store surface height so shores shade right
                } else {
                    // hill shading vs the block to the north
                    var north: Int = if (oz + z > 0) {
                        region.heights[idx - TILE].toInt()
                    } else {
                        neighborNorthHeight(rx, rz, ox + x)
                    }
                    if (z == 0 && north == UNKNOWN.toInt()) {
                        north = job.northEdge?.get(x) ?: UNKNOWN.toInt()
                    }
                    val f = if (north == UNKNOWN.toInt()) {
                        1.0
                    } else if (y > north) {
                        1.0 + 0.10 * s.shading
                    } else if (y < north) {
                        1.0 - 0.16 * s.shading
                    } else {
                        1.0 - 0.04 * s.shading
                    }
                    argb = scale(color, f)
                }
                if (s.chunkGrid && (x == 0 || z == 0)) argb = scale(argb, 0.9)
                region.pixels[idx] = argb or (0xFF shl 24)
                region.heights[idx] = y.toShort()
            }
        }
        region.dirty = true
    }

    /** north neighbor height across a region boundary, only if that region is cached */
    private fun neighborNorthHeight(rx: Int, rz: Int, px: Int): Int {
        val r = regions[key(rx, rz - 1)] ?: return UNKNOWN.toInt()
        return r.heights[(TILE - 1) * TILE + px].toInt()
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        if (t <= 0.0) return a
        val r = ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    private fun scale(c: Int, f: Double): Int {
        val r = ((c shr 16 and 255) * f).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 255) * f).toInt().coerceIn(0, 255)
        val b = ((c and 255) * f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------
    // region cache + disk

    private fun tilePath(level: Int, x: Int, z: Int): Path = tilesDir.resolve(level.toString()).resolve("${x}_$z.png")

    private fun region(rx: Int, rz: Int): Region {
        val k = key(rx, rz)
        regions[k]?.let { return it }
        val pixels = readTile(tilePath(0, rx, rz)) ?: IntArray(TILE * TILE)
        val r = Region(rx, rz, pixels)
        regions[k] = r
        // evict least-recently used, writing it out if needed
        while (regions.size > REGION_CACHE_SIZE) {
            val eldest = regions.entries.iterator().next()
            if (eldest.value.dirty) writeRegion(eldest.value)
            regions.remove(eldest.key)
        }
        return r
    }

    private fun flushRegions() {
        for (r in regions.values) {
            if (r.dirty) writeRegion(r)
        }
    }

    private fun writeRegion(r: Region) {
        writeTile(tilePath(0, r.rx, r.rz), r.pixels)
        onTileWritten?.invoke(0, r.rx, r.rz)
        r.dirty = false
        if (settings.zoomOutLevels > 0) {
            dirtyPyramid[1].add(key(Math.floorDiv(r.rx, 2), Math.floorDiv(r.rz, 2)))
        }
    }

    private fun buildPyramid() {
        val levels = settings.zoomOutLevels.coerceAtMost(dirtyPyramid.size - 1)
        for (level in 1..levels) {
            val set = dirtyPyramid[level]
            if (set.isEmpty()) continue
            val todo = set.toList()
            set.clear()
            for (k in todo) {
                val tx = keyX(k)
                val tz = keyZ(k)
                if (composeZoomTile(level, tx, tz) && level < levels) {
                    dirtyPyramid[level + 1].add(key(Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)))
                }
            }
        }
    }

    /** Build a zoomed-out tile from its 4 children by 2x2 averaging. */
    private fun composeZoomTile(level: Int, tx: Int, tz: Int): Boolean {
        val out = IntArray(TILE * TILE)
        var any = false
        for (q in 0 until 4) {
            val cx = tx * 2 + (q and 1)
            val cz = tz * 2 + (q shr 1)
            val child = childPixels(level - 1, cx, cz) ?: continue
            any = true
            val offX = (q and 1) * (TILE / 2)
            val offZ = (q shr 1) * (TILE / 2)
            for (z in 0 until TILE / 2) {
                val row0 = (z * 2) * TILE
                val row1 = row0 + TILE
                val outRow = (offZ + z) * TILE + offX
                for (x in 0 until TILE / 2) {
                    out[outRow + x] = avg4(child[row0 + x * 2], child[row0 + x * 2 + 1], child[row1 + x * 2], child[row1 + x * 2 + 1])
                }
            }
        }
        if (any) {
            writeTile(tilePath(level, tx, tz), out)
            onTileWritten?.invoke(level, tx, tz)
        }
        return any
    }

    private fun childPixels(level: Int, x: Int, z: Int): IntArray? {
        if (level == 0) regions[key(x, z)]?.let { return it.pixels }
        return readTile(tilePath(level, x, z))
    }

    private fun avg4(a: Int, b: Int, c: Int, d: Int): Int {
        var n = 0
        var r = 0
        var g = 0
        var bl = 0
        for (p in intArrayOf(a, b, c, d)) {
            if (p ushr 24 == 0) continue
            n++
            r += p shr 16 and 255
            g += p shr 8 and 255
            bl += p and 255
        }
        if (n == 0) return 0
        return (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
    }

    private fun readTile(path: Path): IntArray? {
        if (!Files.exists(path)) return null
        return try {
            val img = ImageIO.read(path.toFile()) ?: return null
            if (img.width != TILE || img.height != TILE) return null
            img.getRGB(0, 0, TILE, TILE, null, 0, TILE)
        } catch (e: Exception) {
            null
        }
    }

    /** write to a temp file then atomically swap, so browsers never get half a png */
    private fun writeTile(path: Path, pixels: IntArray) {
        Files.createDirectories(path.parent)
        val img = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, TILE, TILE, pixels, 0, TILE)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        ImageIO.write(img, "png", tmp.toFile())
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        tilesWritten.incrementAndGet()
    }
}
