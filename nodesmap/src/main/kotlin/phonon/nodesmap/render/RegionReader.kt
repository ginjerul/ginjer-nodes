package phonon.nodesmap.render

import org.bukkit.Material
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Reads chunks straight out of Anvil region files (r.X.Z.mca) without the
 * server loading anything. Only what the map needs is decoded: block palettes,
 * packed block states and the WORLD_SURFACE heightmap; everything else in the
 * chunk NBT is skipped.
 *
 * Supports zlib, gzip and uncompressed chunks in the 1.18+ format. Anything
 * else (LZ4, oversized external .mcc chunks, chunks not yet upgraded from an
 * older Minecraft version) is reported as "fallback" so the caller can let the
 * server load it instead.
 */
class RegionReader(
    private val minY: Int,
    /** exclusive */
    private val maxY: Int,
) {
    sealed class Result {
        /** [note] is set when the chunk was drawn even though it isn't marked fully generated */
        class Ok(val chunk: RegionChunk, val note: String? = null) : Result()

        /** not a fully generated chunk; nothing to draw */
        class Skip(val reason: String) : Result()

        /** can't read it here; ask the server to load it */
        class Fallback(val reason: String) : Result()
    }

    /** chunk coordinates present in a region file, from its header only */
    fun listChunks(path: Path, rx: Int, rz: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        try {
            RandomAccessFile(path.toFile(), "r").use { raf ->
                if (raf.length() < 8192) return out
                val header = ByteArray(4096)
                raf.readFully(header)
                for (i in 0 until 1024) {
                    val loc = readInt(header, i * 4)
                    if (loc != 0) out.add((rx * 32 + (i and 31)) to (rz * 32 + (i shr 5)))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * Read every chunk in a region file, north to south. [want] filters chunks;
     * [emit] gets each result. Returns false if the file couldn't be opened.
     */
    fun readRegion(
        path: Path,
        rx: Int,
        rz: Int,
        cancelled: () -> Boolean,
        want: (Int, Int) -> Boolean,
        emit: (Int, Int, Result) -> Unit,
    ): Boolean {
        val raf = try {
            RandomAccessFile(path.toFile(), "r")
        } catch (_: Exception) {
            return false
        }
        raf.use {
            if (raf.length() < 8192) return true
            val header = ByteArray(4096)
            raf.readFully(header)
            for (i in 0 until 1024) {
                if (cancelled()) return true
                val loc = readInt(header, i * 4)
                if (loc == 0) continue
                val cx = rx * 32 + (i and 31)
                val cz = rz * 32 + (i shr 5)
                if (!want(cx, cz)) continue
                val result = try {
                    readChunk(raf, (loc ushr 8).toLong(), loc and 0xFF)
                } catch (e: Exception) {
                    Result.Fallback("unreadable chunk data (${e.javaClass.simpleName})")
                }
                emit(cx, cz, result)
            }
        }
        return true
    }

    private fun readChunk(raf: RandomAccessFile, sectorOffset: Long, sectors: Int): Result {
        if (sectorOffset < 2) return Result.Fallback("bad region header")
        raf.seek(sectorOffset * 4096L)
        val length = raf.readInt()
        if (length <= 1 || length > sectors * 4096) return Result.Fallback("bad chunk length")
        val type = raf.readUnsignedByte()
        if (type and 0x80 != 0) return Result.Fallback("oversized chunk (.mcc file)")
        val bytes = ByteArray(length - 1)
        raf.readFully(bytes)
        val stream: InputStream = when (type) {
            1 -> GZIPInputStream(ByteArrayInputStream(bytes), 8192)
            2 -> InflaterInputStream(ByteArrayInputStream(bytes), java.util.zip.Inflater(), 8192)
            3 -> ByteArrayInputStream(bytes)
            4 -> return Result.Fallback("LZ4 compression")
            else -> return Result.Fallback("compression type $type")
        }
        DataInputStream(stream.buffered(16384)).use { return parse(Nbt(it)) }
    }

    // ------------------------------------------------------------------

    companion object {
        /** generation stages before the surface exists */
        val NO_TERRAIN = setOf("empty", "structure_starts", "structure_references", "biomes", "noise")
    }

    private class RawSection(val y: Int, val palette: Array<Material>, val data: LongArray?)

    private fun parse(nbt: Nbt): Result {
        if (nbt.readUnsignedByte() != Nbt.COMPOUND) return Result.Fallback("not a chunk")
        nbt.readUTF()

        var dataVersion = 0
        var status: String? = null
        var yPos = Int.MIN_VALUE
        val raw = ArrayList<RawSection>(24)
        var surface: LongArray? = null

        nbt.compound { name, type ->
            when {
                name == "DataVersion" && type == Nbt.INT -> {
                    dataVersion = nbt.readInt()
                    true
                }
                name == "Status" && type == Nbt.STRING -> {
                    status = nbt.readUTF()
                    true
                }
                name == "yPos" && (type == Nbt.INT || type == Nbt.BYTE) -> {
                    yPos = if (type == Nbt.INT) nbt.readInt() else nbt.readByte().toInt()
                    true
                }
                name == "sections" && type == Nbt.LIST -> {
                    readSections(nbt, raw)
                    true
                }
                name == "Heightmaps" && type == Nbt.COMPOUND -> {
                    nbt.compound { hn, ht ->
                        if (hn == "WORLD_SURFACE" && ht == Nbt.LONG_ARRAY) {
                            surface = nbt.readLongArray()
                            true
                        } else {
                            false
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 1.18+ chunk format (2860 = 1.18). Older chunks get upgraded by the server when loaded.
        if (dataVersion in 1 until 2860) return Result.Fallback("saved before 1.18")
        if (raw.isEmpty()) return if (dataVersion == 0) Result.Fallback("old chunk format") else Result.Skip("empty")
        // some tools leave out yPos; the lowest section tells us
        if (yPos == Int.MIN_VALUE) yPos = minOf(raw.minOf { it.y }, minY shr 4)
        // Only skip chunks that have no real terrain yet. World tools (Earth maps,
        // WorldPainter, converters) often save finished terrain under an earlier
        // status; those are drawn like any other chunk.
        val st = status?.removePrefix("minecraft:")
        if (st != null && st in NO_TERRAIN) return Result.Skip("no terrain yet (status $st)")
        val note = if (st != null && st != "full") "status $st" else null

        val minSection = minY shr 4
        val count = (maxY - minY) shr 4
        val sections = arrayOfNulls<Section>(count)
        for (r in raw) {
            val i = r.y - minSection
            if (i in 0 until count) sections[i] = Section(r.palette, r.data)
        }

        val heights = IntArray(256)
        val hm = surface
        if (hm != null && hm.isNotEmpty()) {
            val perLong = (256 + hm.size - 1) / hm.size
            val bits = 64 / perLong
            val mask = (1L shl bits) - 1
            for (i in 0 until 256) {
                val v = ((hm[i / perLong] ushr ((i % perLong) * bits)) and mask).toInt()
                heights[i] = (v + yPos * 16).coerceIn(minY, maxY)
            }
        } else {
            // no heightmap saved: find the top non-air block per column
            for (i in 0 until 256) {
                val x = i and 15
                val z = i shr 4
                var top = minY
                loop@ for (si in count - 1 downTo 0) {
                    val s = sections[si] ?: continue
                    if (s.isEmpty) continue
                    for (y in 15 downTo 0) {
                        if (!s.get(x, y, z).isAir) {
                            top = minY + si * 16 + y + 1
                            break@loop
                        }
                    }
                }
                heights[i] = top
            }
        }
        if (note != null && sections.all { it == null || it.isEmpty }) return Result.Skip("empty (status $st)")
        return Result.Ok(RegionChunk(minY, sections, heights), note)
    }

    private fun readSections(nbt: Nbt, out: ArrayList<RawSection>) {
        val elemType = nbt.readUnsignedByte()
        val n = nbt.readInt()
        if (elemType != Nbt.COMPOUND) {
            repeat(n) { nbt.skip(elemType) }
            return
        }
        repeat(n) {
            var y = Int.MIN_VALUE
            var palette: Array<Material>? = null
            var data: LongArray? = null
            nbt.compound { name, type ->
                when {
                    name == "Y" && (type == Nbt.BYTE || type == Nbt.INT) -> {
                        y = if (type == Nbt.BYTE) nbt.readByte().toInt() else nbt.readInt()
                        true
                    }
                    name == "block_states" && type == Nbt.COMPOUND -> {
                        nbt.compound { bn, bt ->
                            when {
                                bn == "palette" && bt == Nbt.LIST -> {
                                    palette = readPalette(nbt)
                                    true
                                }
                                bn == "data" && bt == Nbt.LONG_ARRAY -> {
                                    data = nbt.readLongArray()
                                    true
                                }
                                else -> false
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            val p = palette
            if (y != Int.MIN_VALUE && p != null && p.isNotEmpty()) out.add(RawSection(y, p, data))
        }
    }

    private fun readPalette(nbt: Nbt): Array<Material> {
        val elemType = nbt.readUnsignedByte()
        val n = nbt.readInt()
        if (elemType != Nbt.COMPOUND) {
            repeat(n) { nbt.skip(elemType) }
            return emptyArray()
        }
        return Array(n) {
            var m = Material.AIR
            nbt.compound { name, type ->
                if (name == "Name" && type == Nbt.STRING) {
                    m = BlockColors.byName(nbt.readUTF())
                    true
                } else {
                    false
                }
            }
            m
        }
    }

    private fun readInt(b: ByteArray, o: Int): Int = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or
        ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
}

/** Minimal streaming NBT reader: read what you ask for, skip the rest. */
class Nbt(private val inp: DataInputStream) {
    companion object {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12
    }

    private val scratch = ByteArray(8192)

    fun readUnsignedByte(): Int = inp.readUnsignedByte()

    fun readByte(): Byte = inp.readByte()

    fun readInt(): Int = inp.readInt()

    fun readUTF(): String = inp.readUTF()

    fun readLongArray(): LongArray {
        val n = inp.readInt()
        if (n < 0 || n > 1 shl 20) throw EOFException("bad long array")
        return LongArray(n) { inp.readLong() }
    }

    /** Iterate a compound's entries. Return true from [f] if you consumed the value, false to skip it. */
    inline fun compound(f: (String, Int) -> Boolean) {
        while (true) {
            val type = readUnsignedByte()
            if (type == END) return
            val name = readUTF()
            if (!f(name, type)) skip(type)
        }
    }

    fun skip(type: Int) {
        when (type) {
            BYTE -> skipBytes(1)
            SHORT -> skipBytes(2)
            INT, FLOAT -> skipBytes(4)
            LONG, DOUBLE -> skipBytes(8)
            BYTE_ARRAY -> skipBytes(inp.readInt().toLong())
            STRING -> skipBytes(inp.readUnsignedShort().toLong())
            LIST -> {
                val et = inp.readUnsignedByte()
                val n = inp.readInt()
                when (et) {
                    BYTE -> skipBytes(n.toLong())
                    SHORT -> skipBytes(2L * n)
                    INT, FLOAT -> skipBytes(4L * n)
                    LONG, DOUBLE -> skipBytes(8L * n)
                    else -> repeat(n) { skip(et) }
                }
            }
            COMPOUND -> compound { _, _ -> false }
            INT_ARRAY -> skipBytes(4L * inp.readInt())
            LONG_ARRAY -> skipBytes(8L * inp.readInt())
            END -> {}
            else -> throw EOFException("unknown NBT tag $type")
        }
    }

    private fun skipBytes(n: Long) {
        if (n < 0) throw EOFException("negative length")
        var left = n
        while (left > 0) {
            val r = inp.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (r < 0) throw EOFException()
            left -= r
        }
    }
}
