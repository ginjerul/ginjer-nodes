/**
 * Commands for requesting peace on other towns, nations
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import phonon.nodes.utils.string.filterTownOrNation
import phonon.nodes.war.Treaty

/**
 * @command /peace
 * Offer peace to a town or nation
 *
 * @subcommand /peace [town]
 * Offer peace to a town
 *
 * @subcommand /peace [nation]
 * Offer peace to a nation
 */
public class PeaceCommand :
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
            Message.error(player, "Only the nation's capital town can offer/accept peace")
            return true
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "Only the leader and officers can offer/accept peace")
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
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Peace commands:")
        Message.print(sender, "/peace [town]${ChatColor.WHITE}: Offer/accept peace with town")
        Message.print(sender, "/peace [nation]${ChatColor.WHITE}: Offer/accept peace with nation")
        return
    }

    private fun parseTownOrNationName(player: Player, args: Array<String>, town: Town, townNation: Nation?) {
        val target = args[0]

        var enemyNation = Nodes.nations.get(target)
        if (enemyNation !== null) {
            offerPeace(player, town, enemyNation.capital, townNation, enemyNation)
            return
        }

        // Else a town (resolved to its nation if it has one)
        val enemyTown = Nodes.towns.get(target)
        if (enemyTown !== null) {
            enemyNation = enemyTown.nation
            if (enemyNation !== null) {
                offerPeace(player, town, enemyNation.capital, townNation, enemyNation)
            } else {
                offerPeace(player, town, enemyTown, townNation, enemyNation)
            }
            return
        }

        Message.error(player, "Town or nation \"${target}\" does not exist")
    }

    private fun offerPeace(player: Player, town: Town, enemy: Town, townNation: Nation?, enemyNation: Nation?) {
        if (town === enemy) {
            Message.error(player, "You cannot make a peace treaty with yourself.")
            return
        }

        // false = a new treaty was created
        val result = Treaty.show(player, town, enemy)
        if (result === false) {
            val enemyName = if (enemyNation !== null) {
                "nation ${enemyNation.name}"
            } else {
                "town ${enemy.name}"
            }

            val initiatorName = if (townNation !== null) {
                townNation.name
            } else {
                town.name
            }

            if (townNation !== null) {
                for (r in townNation.residents) {
                    val p = r.onlinePlayer()
                    if (p !== null) {
                        Message.print(p, "Your nation is negotiating peace with $enemyName")
                    }
                }
            } else {
                for (r in town.residents) {
                    val p = r.onlinePlayer()
                    if (p !== null) {
                        Message.print(p, "Your town is negotiating peace with $enemyName")
                    }
                }
            }

            if (enemyNation !== null) {
                for (r in enemyNation.residents) {
                    val p = r.onlinePlayer()
                    if (p !== null) {
                        Message.print(p, "$initiatorName is offering your nation a peace treaty, use \"/peace ${initiatorName}\" to negotiate")
                    }
                }
            } else {
                for (r in enemy.residents) {
                    val p = r.onlinePlayer()
                    if (p !== null) {
                        Message.print(p, "$initiatorName is offering your town a peace treaty, use \"/peace ${initiatorName}\" to negotiate")
                    }
                }
            }
        }
    }
}
