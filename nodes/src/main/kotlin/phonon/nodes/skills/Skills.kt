/*
 * Skills: mining, farming, woodcutting and hunting level up as players do them. Each level gives
 * a small chance of one extra drop from that activity; set levels earn titles (the same titles as
 * exploration, picked with /map title). Everything is under `skills:` in the config.
 */

package phonon.nodes.skills

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.data.Ageable
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Animals
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.poi.PoiData
import phonon.nodes.poi.Pois
import java.util.concurrent.ThreadLocalRandom

object Skills {
    private var enabled = true
    private var xpMultiplier = 1.0
    internal var maxLevel = 50
    private var baseXp = 50.0
    private var exponent = 1.5
    internal var dropPercentPerLevel = 0.5
    internal var dropPercentMax = 25.0
    private var showActionBar = true
    private val miningXp = HashMap<Material, Double>()
    private val farmingXp = HashMap<Material, Double>()
    private var logXp = 2.0
    private var animalXp = 1.0
    private var monsterXp = 2.0
    private val entityXp = HashMap<EntityType, Double>()
    private var titles: Map<Skill, Map<Int, String>> = emptyMap()
    private var flushTask: BukkitTask? = null

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("skills")
        enabled = sec?.getBoolean("enabled", true) ?: true
        xpMultiplier = (sec?.getDouble("xpMultiplier", 1.0) ?: 1.0).coerceIn(0.0, 100.0)
        maxLevel = (sec?.getInt("maxLevel", 50) ?: 50).coerceIn(1, 200)
        baseXp = (sec?.getDouble("baseXp", 50.0) ?: 50.0).coerceAtLeast(1.0)
        exponent = (sec?.getDouble("levelExponent", 1.5) ?: 1.5).coerceIn(0.5, 4.0)
        dropPercentPerLevel = (sec?.getDouble("extraDropPercentPerLevel", 0.5) ?: 0.5).coerceAtLeast(0.0)
        dropPercentMax = (sec?.getDouble("extraDropPercentMax", 25.0) ?: 25.0).coerceIn(0.0, 100.0)
        showActionBar = sec?.getBoolean("showActionBar", true) ?: true

        miningXp.clear()
        readMaterials(sec?.getConfigurationSection("mining.xp"), miningXp)
        farmingXp.clear()
        readMaterials(sec?.getConfigurationSection("farming.xp"), farmingXp)
        logXp = sec?.getDouble("woodcutting.xpPerLog", 2.0) ?: 2.0
        animalXp = sec?.getDouble("hunting.animalXp", 1.0) ?: 1.0
        monsterXp = sec?.getDouble("hunting.monsterXp", 2.0) ?: 2.0
        entityXp.clear()
        val ent = sec?.getConfigurationSection("hunting.xp")
        for (key in ent?.getKeys(false) ?: emptySet()) {
            val type = try {
                EntityType.valueOf(key.uppercase())
            } catch (_: Exception) {
                null
            }
            if (type != null) {
                entityXp[type] = ent?.getDouble(key) ?: 0.0
            }
        }

        val all = HashMap<Skill, Map<Int, String>>()
        for (skill in Skill.values()) {
            val tSec = sec?.getConfigurationSection("titles.${skill.name.lowercase()}")
            val map = java.util.TreeMap<Int, String>()
            for (key in tSec?.getKeys(false) ?: emptySet()) {
                val level = key.toIntOrNull() ?: continue
                map[level] = tSec?.getString(key) ?: continue
            }
            all[skill] = map
        }
        titles = all
    }

    private fun readMaterials(sec: ConfigurationSection?, into: MutableMap<Material, Double>) {
        for (key in sec?.getKeys(false) ?: emptySet()) {
            val material = Material.matchMaterial(key) ?: continue
            into[material] = sec?.getDouble(key) ?: continue
        }
    }

    fun isEnabled(): Boolean = enabled

    fun start(plugin: Plugin) {
        flushTask?.cancel()
        flushTask = null
        SkillData.load()
        if (!enabled) {
            return
        }
        Bukkit.getPluginManager().registerEvents(SkillListener(), plugin)
        Bukkit.getPluginManager().registerEvents(SkillMenuListener(), plugin)
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { SkillData.flush() }, 1200L, 1200L)
    }

    fun shutdown() {
        SkillData.flush()
    }

    // ===================================
    // levels
    // ===================================
    // xp needed to go from `level` to the next one
    fun xpForNext(level: Int): Long = Math.round(baseXp * Math.pow(level.toDouble(), exponent))

    fun levelOf(xp: Long): Int {
        var level = 1
        var left = xp
        while (level < maxLevel) {
            val need = xpForNext(level)
            if (left < need) {
                break
            }
            left -= need
            level++
        }
        return level
    }

    // xp into the current level
    fun progressIn(xp: Long): Long {
        var level = 1
        var left = xp
        while (level < maxLevel) {
            val need = xpForNext(level)
            if (left < need) {
                break
            }
            left -= need
            level++
        }
        return left
    }

    fun totalXpForLevel(level: Int): Long {
        var total = 0L
        for (l in 1 until level.coerceIn(1, maxLevel)) {
            total += xpForNext(l)
        }
        return total
    }

    fun level(player: Player, skill: Skill): Int = levelOf(SkillData.xp(player.uniqueId.toString(), skill))

    fun dropPercent(level: Int): Double = minOf(dropPercentMax, (level - 1) * dropPercentPerLevel)

    // ===================================
    // xp
    // ===================================
    fun addXp(player: Player, skill: Skill, amount: Double) {
        if (!enabled || amount <= 0.0 || player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            return
        }
        val gain = Math.round(amount * xpMultiplier).coerceAtLeast(if (xpMultiplier > 0.0) 1L else 0L)
        if (gain <= 0) {
            return
        }
        val uuid = player.uniqueId.toString()
        val before = SkillData.xp(uuid, skill)
        val capXp = totalXpForLevel(maxLevel)
        val after = minOf(before + gain, capXp)
        if (after == before) {
            return
        }
        SkillData.setXp(uuid, player.name, skill, after)
        val oldLevel = levelOf(before)
        val newLevel = levelOf(after)
        if (newLevel > oldLevel) {
            levelUp(player, skill, newLevel)
        } else if (showActionBar) {
            val into = progressIn(after)
            val text = "${skill.label} $newLevel  +$gain xp" + if (newLevel < maxLevel) "  ($into/${xpForNext(newLevel)})" else ""
            player.sendActionBar(Component.text(text, NamedTextColor.GREEN))
        }
    }

    private fun levelUp(player: Player, skill: Skill, level: Int) {
        Message.print(player, "${ChatColor.GOLD}${skill.label} level $level! ${ChatColor.GRAY}${String.format("%.1f", dropPercent(level))}% chance of an extra drop")
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        val earned = titles[skill] ?: return
        val progress = PoiData.progress(player.uniqueId.toString())
        for ((needed, title) in earned) {
            if (level >= needed && !progress.titles.contains(title)) {
                progress.titles.add(title)
                Message.print(player, "${ChatColor.LIGHT_PURPLE}Title earned: ${Pois.colour(title)} ${ChatColor.GRAY}(/map title)")
                PoiData.saveDiscoveries()
            }
        }
    }

    // ===================================
    // xp sources
    // ===================================
    internal fun miningXpFor(material: Material): Double {
        val base = Material.matchMaterial(material.name.removePrefix("DEEPSLATE_"))
        return miningXp[material] ?: miningXp[base] ?: 0.0
    }

    internal fun farmingXpFor(material: Material): Double = farmingXp[material] ?: 0.0

    internal fun logXpFor(material: Material): Double = if (Tag.LOGS.isTagged(material)) logXp else 0.0

    internal fun huntingXpFor(entity: org.bukkit.entity.LivingEntity): Double {
        val fallback = when (entity) {
            is Monster -> monsterXp
            is Animals -> animalXp
            else -> 0.0
        }
        return entityXp[entity.type] ?: fallback
    }

    // whether this drop gets a bonus item for the player
    internal fun rollExtra(player: Player, skill: Skill): Boolean {
        val chance = dropPercent(level(player, skill))
        return chance > 0.0 && ThreadLocalRandom.current().nextDouble() * 100.0 < chance
    }
}

internal const val NO_XP_TAG = "nodes_noxp"

private val NO_XP_REASONS = setOf(
    CreatureSpawnEvent.SpawnReason.SPAWNER,
    CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
    CreatureSpawnEvent.SpawnReason.BREEDING,
    CreatureSpawnEvent.SpawnReason.EGG,
    CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
)

class SkillListener : Listener {
    // mobs from spawners, eggs and breeding give no hunting xp
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason in NO_XP_REASONS) {
            event.entity.addScoreboardTag(NO_XP_TAG)
        }
    }

    // blocks players placed themselves give no xp, so ores and logs cannot be farmed by replacing them
    private val placed = LinkedHashSet<Long>()

    private fun key(block: org.bukkit.block.Block): Long {
        val x = (block.x.toLong() and 0x3FFFFFF) shl 38
        val z = (block.z.toLong() and 0x3FFFFFF) shl 12
        val y = block.y.toLong() and 0xFFF
        return (x or z or y) xor block.world.name.hashCode().toLong()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val type = event.block.type
        if (Skills.miningXpFor(type) > 0 || Skills.logXpFor(type) > 0) {
            placed.add(key(event.block))
            if (placed.size > 100_000) {
                val it = placed.iterator()
                it.next()
                it.remove()
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        val type = block.type
        if (placed.remove(key(block))) {
            return
        }
        val mining = Skills.miningXpFor(type)
        if (mining > 0) {
            Skills.addXp(player, Skill.MINING, mining)
            return
        }
        val logs = Skills.logXpFor(type)
        if (logs > 0) {
            Skills.addXp(player, Skill.WOODCUTTING, logs)
            return
        }
        val farming = Skills.farmingXpFor(type)
        if (farming > 0) {
            val data = block.blockData
            if (data is Ageable && data.age < data.maximumAge) {
                return
            }
            Skills.addXp(player, Skill.FARMING, farming)
        }
    }

    // the extra drop: one more copy of the first item the block dropped
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDrops(event: BlockDropItemEvent) {
        val type = event.blockState.type
        val skill = when {
            Skills.miningXpFor(type) > 0 -> Skill.MINING
            Skills.logXpFor(type) > 0 -> Skill.WOODCUTTING
            Skills.farmingXpFor(type) > 0 -> Skill.FARMING
            else -> return
        }
        if (skill == Skill.FARMING) {
            val data = event.blockState.blockData
            if (data is Ageable && data.age < data.maximumAge) {
                return
            }
        }
        val first = event.items.firstOrNull() ?: return
        if (Skills.rollExtra(event.player, skill)) {
            val extra = first.itemStack.clone()
            extra.amount = 1
            event.block.world.dropItemNaturally(first.location, extra)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKill(event: EntityDeathEvent) {
        val player = event.entity.killer ?: return
        val entity = event.entity
        if (entity is Player) {
            return
        }
        if (entity.scoreboardTags.contains(NO_XP_TAG)) {
            return
        }
        val xp = Skills.huntingXpFor(entity)
        if (xp <= 0) {
            return
        }
        Skills.addXp(player, Skill.HUNTING, xp)
        if (Skills.rollExtra(player, Skill.HUNTING)) {
            val first = event.drops.firstOrNull() ?: return
            val extra = first.clone()
            extra.amount = 1
            event.drops.add(extra)
        }
    }
}
