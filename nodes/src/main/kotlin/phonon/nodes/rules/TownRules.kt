/*
 * Town rules kept next to the main Nodes save: the PvP toggle (togglePVPMechanic),
 * mob protection (mobProtection), invulnerable towns, war sickness and the
 * rename cooldown. Per-town values are saved in plugins/nodes/townflags.json.
 * Everything here stays off until its config option is turned on.
 */

package phonon.nodes.rules

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.DiplomaticRelationship
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Town
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class TownFlag(
    var pvp: Boolean = true,
    var pvpChangedAt: Long = 0L,
    var renamedAt: Long = 0L,
)

object TownFlags {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/townflags.json")
    private val flags: LinkedHashMap<String, TownFlag> = LinkedHashMap()

    fun load() {
        flags.clear()
        if (!Files.exists(path)) {
            return
        }
        try {
            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            val type = object : TypeToken<LinkedHashMap<String, TownFlag>>() {}.type
            val loaded: LinkedHashMap<String, TownFlag>? = gson.fromJson(text, type)
            loaded?.let { flags.putAll(it) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Rules] Could not read townflags.json: ${e.message}")
        }
    }

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("townflags.tmp")
            Files.write(tmp, gson.toJson(flags).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Rules] Could not save townflags.json: ${e.message}")
        }
    }

    private fun of(town: Town): TownFlag? = flags[town.uuid.toString()]

    private fun edit(town: Town, change: (TownFlag) -> Unit) {
        change(flags.getOrPut(town.uuid.toString()) { TownFlag() })
        save()
    }

    // false only when the PvP toggle is on and the town switched PvP off
    fun pvpEnabled(town: Town): Boolean = !Config.togglePVPMechanic || (of(town)?.pvp ?: true)

    // players change it with a cooldown; admins set it directly
    fun setPvp(town: Town, enabled: Boolean) = edit(town) {
        it.pvp = enabled
        it.pvpChangedAt = System.currentTimeMillis()
    }

    fun pvpCooldownLeft(town: Town): Long {
        val changed = of(town)?.pvpChangedAt ?: return 0L
        return maxOf(0L, changed + Config.pvpToggleCooldown - System.currentTimeMillis())
    }

    fun renameCooldownLeft(town: Town): Long {
        val renamed = of(town)?.renamedAt ?: return 0L
        return maxOf(0L, renamed + Config.townRenameCooldown - System.currentTimeMillis())
    }

    fun markRenamed(town: Town) = edit(town) { it.renamedAt = System.currentTimeMillis() }

    fun durationText(ms: Long): String {
        val minutes = (ms + 59_999L) / 60_000L
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
}

object TownRules {
    // the town that holds the land at a location (an occupier counts), or null
    fun townAt(loc: Location): Town? {
        val chunk = Nodes.getTerritoryChunkFromCoord(Coord(Math.floorDiv(loc.blockX, 16), Math.floorDiv(loc.blockZ, 16))) ?: return null
        return chunk.occupier ?: chunk.territory.town
    }

    fun start(plugin: Plugin) {
        // war sickness: weakness and slowness for survival players standing on neutral land during war
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                if (!Config.warSickness || Nodes.war.enabled != true) {
                    return@Runnable
                }
                for (player in Bukkit.getOnlinePlayers()) {
                    if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) {
                        continue
                    }
                    val town = townAt(player.location) ?: continue
                    if (Nodes.getRelationshipOfPlayerToTown(player, town) == DiplomaticRelationship.NEUTRAL) {
                        player.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 160, 0, true, false))
                        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 160, 0, true, false))
                    }
                }
            },
            100L,
            100L,
        )
    }
}

class TownRulesListener : Listener {
    private fun attackerOf(damager: Entity): Player? = when (damager) {
        is Player -> damager
        is Projectile -> damager.shooter as? Player
        else -> null
    }

    // players in towns listed in invulnerabilityTowns take no damage at all
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAnyDamage(event: EntityDamageEvent) {
        if (Config.invulnerabilityTowns.isEmpty()) {
            return
        }
        val victim = event.entity as? Player ?: return
        val town = Nodes.getResident(victim)?.town ?: return
        if (Config.invulnerabilityTowns.contains(town.uuid)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val attacker = attackerOf(event.damager) ?: return
        val victim = event.entity

        if (victim is Player) {
            pvpRule(event, attacker, victim)
        } else if (Config.mobProtection) {
            mobRule(event, attacker, victim)
        }
    }

    // PvP is blocked on land of a town that turned it off, except against enemies while a war is on
    private fun pvpRule(event: EntityDamageByEntityEvent, attacker: Player, victim: Player) {
        if (!Config.togglePVPMechanic || attacker.uniqueId == victim.uniqueId) {
            return
        }
        val town = TownRules.townAt(victim.location) ?: return
        if (TownFlags.pvpEnabled(town)) {
            return
        }
        if (Nodes.war.enabled == true && Nodes.getRelationshipOfPlayerToPlayer(victim, attacker) == DiplomaticRelationship.ENEMY) {
            return
        }
        event.isCancelled = true
        Message.error(attacker, "PvP is turned off in ${town.name}")
    }

    // listed animals inside a town can only be hurt by that town's own members and nation
    private fun mobRule(event: EntityDamageByEntityEvent, attacker: Player, mob: Entity) {
        val protectedHere = Config.protectedMobs.contains(mob.type) ||
            (Config.protectedMobsIfPVPOff.contains(mob.type) && Config.togglePVPMechanic)
        if (!protectedHere) {
            return
        }
        val town = TownRules.townAt(mob.location) ?: return
        if (Config.protectedMobsIfPVPOff.contains(mob.type) && !Config.protectedMobs.contains(mob.type) && TownFlags.pvpEnabled(town)) {
            return
        }
        val relation = Nodes.getRelationshipOfPlayerToTown(attacker, town)
        if (relation == DiplomaticRelationship.TOWN || relation == DiplomaticRelationship.NATION) {
            return
        }
        event.isCancelled = true
        Message.error(attacker, "These animals are protected by ${town.name}")
    }
}
