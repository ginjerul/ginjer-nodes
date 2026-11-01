package phonon.nodesmap.render

import org.bukkit.ChunkSnapshot
import org.bukkit.Material

/**
 * What the tile renderer needs from a chunk. Implemented by live server
 * snapshots (changed chunks) and by chunks read straight from region files
 * (full renders).
 */
interface ColumnSource {
    /** first air block above the highest block in the column (like a heightmap value) */
    fun topY(x: Int, z: Int): Int

    fun type(x: Int, y: Int, z: Int): Material
}

class SnapshotSource(private val s: ChunkSnapshot) : ColumnSource {
    override fun topY(x: Int, z: Int): Int = s.getHighestBlockYAt(x, z)

    override fun type(x: Int, y: Int, z: Int): Material = s.getBlockType(x, y, z)
}

/** One 16x16x16 section decoded from region-file NBT. */
class Section(
    private val palette: Array<Material>,
    private val data: LongArray?,
) {
    private val bits: Int
    private val perLong: Int
    private val mask: Long

    init {
        val b = if (data == null || data.isEmpty()) 0 else maxOf(4, 64 / ((4096 + data.size - 1) / data.size))
        bits = b
        perLong = if (b == 0) 1 else 64 / b
        mask = if (b == 0) 0L else (1L shl b) - 1
    }

    fun get(x: Int, y: Int, z: Int): Material {
        if (bits == 0) return palette[0]
        val i = (y shl 8) or (z shl 4) or x
        val l = data!![i / perLong]
        val idx = ((l ushr ((i % perLong) * bits)) and mask).toInt()
        return if (idx < palette.size) palette[idx] else Material.AIR
    }

    val isEmpty: Boolean get() = bits == 0 && palette[0].isAir
}

/** A chunk read from disk: sections bottom to top, plus surface heights. */
class RegionChunk(
    private val minY: Int,
    private val sections: Array<Section?>,
    private val heights: IntArray,
) : ColumnSource {
    override fun topY(x: Int, z: Int): Int = heights[(z shl 4) or x]

    override fun type(x: Int, y: Int, z: Int): Material {
        val si = (y - minY) shr 4
        if (si < 0 || si >= sections.size) return Material.AIR
        val s = sections[si] ?: return Material.AIR
        return s.get(x, (y - minY) and 15, z)
    }
}
