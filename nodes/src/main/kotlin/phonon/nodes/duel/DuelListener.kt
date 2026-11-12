/*
 * Rules inside a duel: only the two duelists can hurt each other (this overrides town PvP and
 * friendly fire rules), a killing blow ends the duel instead of killing, and duelists and
 * spectators cannot leave, teleport or run other commands.
 */

package phonon.nodes.duel

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin

class DuelListener(private val plugin: Plugin) : Listener {
    private fun attackerOf(event: EntityDamageByEntityEvent): Player? {
        val damager = event.damager
        if (damager is Player) {
            return damager
        }
        if (damager is Projectile) {
            return damager.shooter as? Player
        }
        return null
    }

    // runs after the town and friendly fire rules (registered last) and has the final say
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(event: EntityDamageEvent) {
        val attacker = if (event is EntityDamageByEntityEvent) attackerOf(event) else null
        val victim = event.entity as? Player
        val victimDuel = victim?.let { Duels.duels[it.uniqueId] }
        val attackerDuel = attacker?.let { Duels.duels[it.uniqueId] }

        if (victim == null || victimDuel == null) {
            // a duelist cannot hurt people outside the duel
            if (attackerDuel != null && event.entity is Player) {
                event.isCancelled = true
            }
            return
        }
        if (attacker != null && !victimDuel.has(attacker.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (victimDuel.counting) {
            event.isCancelled = true
            return
        }
        if (attacker != null) {
            event.isCancelled = false
        }
        if (event.isCancelled) {
            return
        }
        if (event.finalDamage >= victim.health) {
            event.isCancelled = true
            Duels.end(victimDuel, victimDuel.opponent(victim.uniqueId), "knockout")
        }
    }

    // XC guns (muskets etc.) do not go through Bukkit damage events, so their hits are handled
    // here through XC's own event, with the same rules as onDamage
    fun registerXc() {
        val eventClass = try {
            Class.forName("phonon.xc.event.XCProjectileDamageEvent")
        } catch (_: ClassNotFoundException) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val clazz = eventClass as Class<out Event>
        plugin.server.pluginManager.registerEvent(
            clazz,
            object : Listener {},
            EventPriority.HIGHEST,
            { _, event -> onXcHit(event) },
            plugin,
            false,
        )
    }

    private fun xcCall(event: Event, name: String): Any? = try {
        event.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(event)
    } catch (_: Exception) {
        null
    }

    private fun onXcHit(event: Event) {
        try {
            val victim = xcCall(event, "getTarget") as? Player ?: return
            val attacker = xcCall(event, "getSource") as? Player
            val victimDuel = Duels.duels[victim.uniqueId]
            val attackerDuel = attacker?.let { Duels.duels[it.uniqueId] }
            var cancel = false
            var knockout = false
            if (victimDuel == null) {
                cancel = attackerDuel != null
            } else if (attacker == null || !victimDuel.has(attacker.uniqueId) || victimDuel.counting) {
                cancel = true
            } else {
                val damage = (xcCall(event, "getDamage") as? Number)?.toDouble()
                // when XC does not tell the damage, treat a hit on a badly hurt player as the killing blow
                val lethal = if (damage != null) damage >= victim.health + victim.absorptionAmount else victim.health <= 10.0
                if (lethal) {
                    cancel = true
                    knockout = true
                } else {
                    event.javaClass.methods.firstOrNull { it.name == "setCancelled" && it.parameterCount == 1 }?.invoke(event, false)
                }
            }
            if (cancel) {
                event.javaClass.methods.firstOrNull { it.name == "setCancelled" && it.parameterCount == 1 }?.invoke(event, true)
            }
            if (knockout && victimDuel != null) {
                Duels.end(victimDuel, victimDuel.opponent(victim.uniqueId), "knockout")
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Duels] XC hook error: ${e.message}")
        }
    }

    // if a duelist still died (the restore could not run while dead), put them back on respawn
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val id = event.player.uniqueId
        if (DuelData.pending(id.toString()) == null || Duels.duels.containsKey(id)) {
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable { if (!Duels.busy(event.player)) Duels.restore(id, null) }, 2L)
    }

    // safety net if a duelist dies anyway (another plugin, /kill): the duel ends and nothing drops
    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        val duel = Duels.duels[event.entity.uniqueId] ?: return
        event.keepInventory = true
        event.keepLevel = true
        event.drops.clear()
        event.droppedExp = 0
        Duels.end(duel, duel.opponent(event.entity.uniqueId), "knockout")
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        val duel = Duels.duels[id]
        if (duel != null) {
            Duels.end(duel, duel.opponent(id), "${event.player.name} left the game")
            return
        }
        val watched = Duels.watching.remove(id) ?: return
        watched.spectators.remove(id)
        Duels.restore(id, null)
    }

    // anyone left over from a crash or a restore that had to wait for them to come back
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val id = event.player.uniqueId
        if (DuelData.pending(id.toString()) == null) {
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable { if (!Duels.busy(event.player)) Duels.restore(id, null) }, 5L)
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val duel = Duels.duels[event.player.uniqueId] ?: return
        if (!duel.counting) {
            return
        }
        val from = event.from
        val to = event.to ?: return
        if (from.blockX != to.blockX || from.blockZ != to.blockZ) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val id = event.player.uniqueId
        if (Duels.moving.contains(id)) {
            return
        }
        if (Duels.busy(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!Duels.busy(player) || player.hasPermission("nodes.duel.bypass")) {
            return
        }
        val root = event.message.removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()
        if (!Duels.isAllowed(root)) {
            event.isCancelled = true
            player.sendMessage("§cYou can only use /duel until the duel is over")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val duel = Duels.duels[event.player.uniqueId] ?: return
        if (duel.kit != null) {
            event.isCancelled = true
        }
    }
}
