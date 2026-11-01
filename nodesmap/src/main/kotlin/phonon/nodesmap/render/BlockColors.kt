package phonon.nodesmap.render

import org.bukkit.Material

/**
 * Material -> RGB lookup table, built once on the main thread.
 *
 * Uses the game's own map colors (BlockData.getMapColor) as the base so every
 * block, including ones added in future versions, gets a sensible color.
 * A handful of very common surface blocks are overridden with biome-ish tints
 * that look nicer than the flat vanilla map palette.
 *
 * TRANSPARENT entries are skipped while scanning down a column.
 */
object BlockColors {
    const val TRANSPARENT: Int = -1

    private var table: IntArray = IntArray(0)
    private var waterLike: BooleanArray = BooleanArray(0)

    /** "minecraft:oak_log" -> OAK_LOG, for blocks read from region files */
    @Volatile private var names: Map<String, Material> = emptyMap()

    fun byName(name: String): Material {
        names[name]?.let { return it }
        return if (name.indexOf(':') < 0) names["minecraft:$name"] ?: Material.AIR else Material.AIR
    }

    private val overrides: Map<Material, Int> = mapOf(
        Material.WATER to 0x3F76E4,
        Material.BUBBLE_COLUMN to 0x3F76E4,
        Material.GRASS_BLOCK to 0x7CB559,
        Material.SHORT_GRASS to TRANSPARENT,
        Material.TALL_GRASS to TRANSPARENT,
        Material.FERN to TRANSPARENT,
        Material.LARGE_FERN to TRANSPARENT,
        Material.OAK_LEAVES to 0x4E9A2A,
        Material.BIRCH_LEAVES to 0x6E9B4C,
        Material.SPRUCE_LEAVES to 0x3F6A3F,
        Material.JUNGLE_LEAVES to 0x3E9B1F,
        Material.ACACIA_LEAVES to 0x5E9A26,
        Material.DARK_OAK_LEAVES to 0x3D7F1E,
        Material.MANGROVE_LEAVES to 0x5B8F2B,
        Material.VINE to 0x4E9A2A,
        Material.LILY_PAD to 0x208030,
        Material.SAND to 0xDBD3A0,
        Material.SANDSTONE to 0xD8CB94,
        Material.SNOW to 0xF4F8F8,
        Material.SNOW_BLOCK to 0xF4F8F8,
        Material.ICE to 0x9AB6EE,
        Material.PACKED_ICE to 0x8DAEEB,
        Material.DIRT_PATH to 0x9C8547,
        Material.FARMLAND to 0x7A5531,
        Material.STONE to 0x7E7E7E,
        Material.DEEPSLATE to 0x575757,
        Material.GRAVEL to 0x86807E,
        Material.LAVA to 0xD25A12,
        Material.TORCH to TRANSPARENT,
        Material.WALL_TORCH to TRANSPARENT,
        Material.LIGHT to TRANSPARENT,
        Material.BARRIER to TRANSPARENT,
        Material.STRUCTURE_VOID to TRANSPARENT,
        Material.TRIPWIRE to TRANSPARENT,
        Material.GLASS_PANE to TRANSPARENT,
    )

    private val waterMaterials = setOf(
        Material.WATER,
        Material.BUBBLE_COLUMN,
        Material.KELP,
        Material.KELP_PLANT,
        Material.SEAGRASS,
        Material.TALL_SEAGRASS,
    )

    /** Must be called on the main thread (creates block data). */
    fun init() {
        val mats = Material.values()
        val t = IntArray(mats.size) { TRANSPARENT }
        val w = BooleanArray(mats.size)
        val n = HashMap<String, Material>(2048)
        for (m in mats) {
            if (m.isLegacy || !m.isBlock) continue
            try {
                n[m.key.toString()] = m
            } catch (_: Throwable) {}
            w[m.ordinal] = m in waterMaterials
            val o = overrides[m]
            if (o != null) {
                t[m.ordinal] = o
                continue
            }
            if (m.isAir) continue
            val rgb = try {
                m.createBlockData().mapColor.asRGB()
            } catch (_: Throwable) {
                0
            }
            // map color NONE (0) = invisible on vanilla maps (glass, etc.)
            t[m.ordinal] = if (rgb == 0) TRANSPARENT else rgb
        }
        // seagrass/kelp themselves render as water
        for (m in waterMaterials) t[m.ordinal] = overrides[Material.WATER]!!
        table = t
        waterLike = w
        names = n
    }

    fun color(m: Material): Int = table[m.ordinal]

    fun isWater(m: Material): Boolean = waterLike[m.ordinal]
}
