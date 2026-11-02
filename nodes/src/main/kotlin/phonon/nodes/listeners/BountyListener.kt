package phonon.nodes.listeners

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import phonon.nodes.Nodes
import phonon.nodes.bounty.Bounties

public class BountyListener : Listener {

    // only a direct player hit or a player-fired projectile counts as the kill
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val damage = victim.lastDamageCause as? EntityDamageByEntityEvent ?: return
        val damager = damage.damager
        val hunter: Player? = when {
            damager is Player -> damager
            damager is Projectile -> damager.shooter as? Player
            else -> null
        }
        if (hunter != null) {
            Bounties.handleKill(hunter, victim)
        }
    }

    @EventHandler
    public fun onJoin(event: PlayerJoinEvent) {
        val plugin = Nodes.plugin ?: return
        val player = event.player
        Bukkit.getScheduler().runTask(plugin, Runnable { Bounties.deliverPending(player) })
    }
}
