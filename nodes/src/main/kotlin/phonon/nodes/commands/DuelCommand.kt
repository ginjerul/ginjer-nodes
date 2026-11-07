/*
 * /duel <player> [kit|own] [wager], /duel accept|deny|cancel, /duel spectate <player>,
 * /duel leave, /duel top, /duel stats [player], /duel kits
 */

package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.duel.DuelData
import phonon.nodes.duel.Duels

class DuelCommand :
    CommandExecutor,
    TabCompleter {
    private val subs = listOf("accept", "deny", "cancel", "spectate", "leave", "top", "stats", "kits", "help")

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        if (sub == "top") {
            Duels.showTop(sender)
            return true
        }
        if (sub == "kits") {
            Message.print(sender, "Duel kits: ${Duels.kitNames()}")
            return true
        }
        if (sub == "stats") {
            val name = args.getOrNull(1) ?: sender.name
            Duels.showStats(sender, name)
            return true
        }
        val player = sender as? Player
        if (player == null) {
            Message.error(sender, "Only players can duel")
            return true
        }
        if (sub == null || sub == "help") {
            help(player)
            return true
        }
        when (sub) {
            "accept" -> Duels.accept(player, args.getOrNull(1))
            "deny" -> Duels.deny(player)
            "cancel" -> Duels.cancel(player)
            "leave" -> Duels.leave(player)
            "spectate", "watch" -> {
                val target = args.getOrNull(1)
                if (target == null) {
                    Message.error(player, "Usage: /duel spectate <player>")
                } else {
                    Duels.spectate(player, target)
                }
            }
            else -> challenge(player, args)
        }
        return true
    }

    private fun challenge(player: Player, args: Array<String>) {
        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            Message.error(player, "${args[0]} is not online")
            return
        }
        var kit: String? = null
        var wager: String? = null
        for (arg in args.drop(1)) {
            if (arg.equals("own", ignoreCase = true) || DuelData.kit(arg) != null) {
                kit = arg
            } else {
                wager = arg
            }
        }
        Duels.challenge(player, target, kit, wager)
    }

    private fun help(player: Player) {
        Message.print(player, "${ChatColor.GOLD}${ChatColor.BOLD}Duels")
        Message.print(player, "${ChatColor.WHITE}/duel <player> [kit|own] [wager]${ChatColor.GRAY}: Challenge someone")
        Message.print(player, "${ChatColor.WHITE}/duel accept [player] | deny | cancel${ChatColor.GRAY}: Answer or withdraw a challenge")
        Message.print(player, "${ChatColor.WHITE}/duel spectate <player> | leave${ChatColor.GRAY}: Watch a duel, or go back (leaving your own duel forfeits)")
        Message.print(player, "${ChatColor.WHITE}/duel top | stats [player] | kits${ChatColor.GRAY}: Records and kits")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        val online = Bukkit.getOnlinePlayers().map { it.name }
        return when {
            args.size == 1 -> (subs + online).filter { it.startsWith(args[0], ignoreCase = true) }
            args.size == 2 && args[0].lowercase() in listOf("accept", "spectate", "watch", "stats") -> online.filter { it.startsWith(args[1], ignoreCase = true) }
            args.size >= 2 && args[0].lowercase() !in subs -> (listOf("own") + DuelData.kits().map { it.name }).filter { it.startsWith(args.last(), ignoreCase = true) }
            else -> emptyList()
        }
    }
}
