/*
 * One-off costs paid through the economy ledger: creating a town (from the
 * player's wallet) and claiming territory (from the town treasury). The money
 * leaves circulation. All of it is off unless economy.costs is set.
 */

package phonon.nodes.economy

import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.objects.Town

object EconomyCosts {
    fun townCreationCost(): Long = if (Economy.enabled) Economy.townCreationCost else 0L

    // cost of claiming a territory that needs `claimPower` claim power
    fun claimCost(claimPower: Int): Long = if (Economy.enabled) Math.multiplyExact(Economy.claimCostPerPower, claimPower.toLong()) else 0L

    fun walletCovers(player: Player, cents: Long): Boolean = cents <= 0 || EconomyExtras.canPay(player, cents)

    fun townCovers(town: Town, cents: Long): Boolean = cents <= 0 || Economy.balance("town-${town.uuid}") >= cents

    fun chargeWallet(player: Player, cents: Long, reason: String) {
        if (cents > 0) {
            charge("wallet-${player.uniqueId}", cents, reason)
        }
    }

    fun chargeTown(town: Town, cents: Long, reason: String) {
        if (cents > 0) {
            charge("town-${town.uuid}", cents, reason)
        }
    }

    private fun charge(account: String, cents: Long, reason: String) {
        Economy.transaction {
            Economy.requireAvailable(account)
            Economy.move(account, "upkeep", cents, reason)
        }
    }

    // a captured town pays the attacker: economy.plunder.perClaimPower for every claim power the town
    // used, but never more than maxPercentOfTreasury of its treasury. Returns what was taken.
    fun plunder(from: Town, to: Town, claimPower: Int): Long {
        if (!Economy.enabled || Economy.plunderPerPower <= 0 || from === to) {
            return 0L
        }
        val source = "town-${from.uuid}"
        val held = Economy.balance(source)
        val wanted = Math.multiplyExact(Economy.plunderPerPower, maxOf(0, claimPower).toLong())
        val cap = (held * Economy.plunderMaxPercent / 100.0).toLong()
        val take = minOf(wanted, cap, held)
        if (take <= 0) {
            return 0L
        }
        return try {
            Economy.transaction {
                Economy.move(source, "town-${to.uuid}", take, "plunder")
            }
            take
        } catch (e: Exception) {
            Nodes.logger?.warning("[Economy] Plunder from ${from.name} failed: ${e.message}")
            0L
        }
    }

    fun show(cents: Long): String = Economy.show(cents)
}
