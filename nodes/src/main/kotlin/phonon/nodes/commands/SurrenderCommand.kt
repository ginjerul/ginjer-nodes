package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Town
import phonon.nodes.surrender.Surrender

class SurrenderCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return true
        }
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "confirm" -> {
                val town = leaderTown(sender) ?: return true
                Surrender.confirm(sender, town)?.let { Message.error(sender, it) }
            }
            "cancel" -> Surrender.cancel(sender)
            else -> {
                val name = args.joinToString(" ")
                val town = leaderTown(sender) ?: return true
                val target = Nodes.getNationFromName(name)
                if (target == null) {
                    Message.error(sender, "Nation not found: $name")
                    return true
                }
                Surrender.request(sender, town, target)?.let { Message.error(sender, it) }
            }
        }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Surrender commands:")
        Message.print(sender, "/surrender <nation>${ChatColor.WHITE}: Join that nation with your town (leader)")
        Message.print(sender, "${ChatColor.GRAY}Territories and income stay. You inherit that nation's wars and alliances. Cooldown 1 day.")
        Message.print(sender, "${ChatColor.GRAY}Vassals cannot surrender.")
    }

    private fun leaderTown(player: Player): Town? {
        val resident = Nodes.getResident(player)
        val town = resident?.town
        if (town == null) {
            Message.error(player, "You are not in a town")
            return null
        }
        if (resident !== town.leader) {
            Message.error(player, "Only the town leader can surrender")
            return null
        }
        return town
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return Nodes.nations.keys.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
