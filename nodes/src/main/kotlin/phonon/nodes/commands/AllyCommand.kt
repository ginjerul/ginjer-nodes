/**
 * Commands for offering/accepting alliances
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import phonon.nodes.utils.string.filterTownOrNation
import phonon.nodes.war.Alliance
import phonon.nodes.war.AllianceRequest
import phonon.nodes.war.ErrorAllyRequestAlreadyAllies
import phonon.nodes.war.ErrorAllyRequestEnemies
import phonon.nodes.war.ErrorPeaceRequestAlreadyCreated

/**
 * @command /ally
 * Offer or break alliances with towns or nations
 *
 * @subcommand /ally [town]
 * Offer alliance to a town
 *
 * @subcommand /ally [nation]
 * Offer alliance to a nation
 */
public class AllyCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (args.size == 0) {
            printHelp(sender)
            return true
        }

        if (!(sender is Player)) {
            return true
        }

        val player: Player = sender
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return true
        }

        val town = resident.town
        if (town == null) {
            return true
        }

        val nation = town.nation
        if (nation !== null && town !== nation.capital) {
            Message.error(player, "Only the nation's capital town can offer/accept alliances")
            return true
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "Only the leader and officers can offer/accept alliances")
            return true
        }

        parseTownOrNationName(player, args, town, nation)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size > 0) {
            return filterTownOrNation(args[0])
        }

        return listOf()
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Ally commands:")
        Message.print(sender, "/ally [town]${ChatColor.WHITE}: Offer/accept alliance with town")
        Message.print(sender, "/ally [nation]${ChatColor.WHITE}: Offer/accept alliance with nation")
        Message.print(sender, "/unally [town]${ChatColor.WHITE}: Break alliance with town")
        Message.print(sender, "/unally [nation]${ChatColor.WHITE}: Break alliance with nation")
        return
    }

    private fun parseTownOrNationName(player: Player, args: Array<String>, town: Town, townNation: Nation?) {
        val target = args[0]

        var otherNation = Nodes.nations.get(target)
        if (otherNation !== null) {
            offerAlliance(player, town, otherNation.capital, townNation, otherNation)
            return
        }

        // Else a town (resolved to its nation if it has one)
        val otherTown = Nodes.towns.get(target)
        if (otherTown !== null) {
            otherNation = otherTown.nation
            if (otherNation !== null) {
                offerAlliance(player, town, otherNation.capital, townNation, otherNation)
            } else {
                offerAlliance(player, town, otherTown, townNation, otherNation)
            }
            return
        }

        Message.error(player, "Town or nation \"${target}\" does not exist")
    }

    // blocks between the closest pair of town homes of the two sides (a nation counts all of its
    // towns); null when a home position is unknown
    private fun distanceBetweenSides(town: Town, townNation: Nation?, other: Town, otherNation: Nation?): Double? {
        val mine = (townNation?.towns ?: setOf(town)).mapNotNull { homeChunk(it) }
        val theirs = (otherNation?.towns ?: setOf(other)).mapNotNull { homeChunk(it) }
        if (mine.isEmpty() || theirs.isEmpty()) {
            return null
        }
        var best = Double.MAX_VALUE
        for (a in mine) {
            for (b in theirs) {
                val dx = (a.x - b.x).toDouble()
                val dz = (a.z - b.z).toDouble()
                best = minOf(best, Math.sqrt(dx * dx + dz * dz) * 16.0)
            }
        }
        return best
    }

    private fun homeChunk(town: Town): Coord? = Nodes.territories[town.home]?.core

    // The alliance forms once both sides have offered
    private fun offerAlliance(player: Player, town: Town, other: Town, townNation: Nation?, otherNation: Nation?) {
        if (town === other) {
            Message.error(player, "You cannot make a peace treaty with yourself.")
            return
        }

        if (Config.allyProximity && !player.hasPermission("nodes.ally.proximity.bypass")) {
            val blocks = distanceBetweenSides(town, townNation, other, otherNation)
            if (blocks != null && blocks > Config.allyProximityDistance) {
                val who = otherNation?.name ?: other.name
                Message.error(player, "$who is too far away to ally (${blocks.toInt()} blocks between your closest towns, the limit is ${Config.allyProximityDistance.toInt()})")
                return
            }
        }

        val result = Alliance.request(town, other)
        if (result.isSuccess) {
            val thisSideName = if (townNation !== null) {
                townNation.name
            } else {
                town.name
            }

            val otherSideName = if (otherNation !== null) {
                otherNation.name
            } else {
                other.name
            }

            when (result.getOrNull()) {
                AllianceRequest.NEW -> {
                    val thisSideMsg = "You are offering an alliance to $otherSideName"
                    for (r in town.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, thisSideMsg)
                        }
                    }

                    val otherSideMsg = "$thisSideName is offering an alliance, use \"/ally ${thisSideName}\" to accept"
                    for (r in other.residents) {
                        val player = r.player()
                        if (player !== null) {
                            Message.print(player, otherSideMsg)
                        }
                    }
                }

                AllianceRequest.ACCEPTED -> {
                    Message.broadcast("$thisSideName has formed an alliance with $otherSideName")
                } null -> {}
            }
        } else {
            when (result.exceptionOrNull()) {
                ErrorAllyRequestEnemies -> Message.error(player, "You cannot ally an enemy")
                ErrorAllyRequestAlreadyAllies -> Message.error(player, "You are already allied with this town or nation")
                ErrorPeaceRequestAlreadyCreated -> Message.error(player, "You already sent an alliance request")
            }
        }

        // TODO alliance requests
    }
}
