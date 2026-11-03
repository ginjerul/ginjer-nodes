/*
 * One-off costs paid through the economy ledger: creating a town (from the
 * player's wallet) and claiming territory (from the town treasury). The money
 * leaves circulation. All of it is off unless economy.costs is set.
 */

package phonon.nodes.economy

import org.bukkit.entity.Player
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

    fun show(cents: Long): String = Economy.show(cents)
}
