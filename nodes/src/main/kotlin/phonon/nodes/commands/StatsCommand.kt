package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.stats.PlayerStats

/**
 * /stats [player]
 * /kdr [player]
 * /top <kills|deaths|kdr|towns|nations|war>
 */
class StatsCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (label.lowercase()) {
            "leaderboard", "lb" -> cmdTop(sender, args)
            else -> cmdStats(sender, args)
        }
        return true
    }

    private fun cmdStats(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            if (sender !is Player) {
                Message.error(sender, "Usage: /stats <player>")
                return
            }
            if (!sender.hasPermission("nodes.stats.self") && !sender.isOp) {
                Message.error(sender, "No permission")
                return
            }
            PlayerStats.printPlayer(sender, sender.name, sender.uniqueId)
            return
        }
        if (!sender.hasPermission("nodes.stats.other") && !sender.isOp) {
            Message.error(sender, "No permission to view other players")
            return
        }
        val offline = Bukkit.getOfflinePlayer(args[0])
        if (offline.name == null && !offline.hasPlayedBefore() && !offline.isOnline) {
            Message.error(sender, "Unknown player: ${args[0]}")
            return
        }
        val name = offline.name ?: args[0]
        PlayerStats.printPlayer(sender, name, offline.uniqueId)
    }

    private fun cmdKdr(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            if (sender !is Player) {
                Message.error(sender, "Usage: /kdr <player>")
                return
            }
            if (!sender.hasPermission("nodes.stats.self") && !sender.isOp) {
                Message.error(sender, "No permission")
                return
            }
            val rec = PlayerStats.get(sender.uniqueId)
            Message.print(sender, "${ChatColor.AQUA}Your KDR: ${ChatColor.WHITE}${rec.kdrString()} ${ChatColor.GRAY}(${rec.kills}K/${rec.deaths}D)")
            return
        }
        if (!sender.hasPermission("nodes.stats.other") && !sender.isOp) {
            Message.error(sender, "No permission")
            return
        }
        val offline = Bukkit.getOfflinePlayer(args[0])
        val name = offline.name ?: args[0]
        val rec = PlayerStats.get(offline.uniqueId)
        Message.print(sender, "${ChatColor.AQUA}$name KDR: ${ChatColor.WHITE}${rec.kdrString()} ${ChatColor.GRAY}(${rec.kills}K/${rec.deaths}D)")
    }

    private fun cmdTop(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("nodes.stats.self") && !sender.isOp) {
            Message.error(sender, "No permission")
            return
        }
        if (args.isEmpty()) {
            Message.print(sender, "Usage: /leaderboard <kills|deaths|kdr|towns|nations|war>")
            return
        }
        when (args[0].lowercase()) {
            "kills" -> PlayerStats.printTop(sender, "Top 10 Kills", PlayerStats.topKills(10))
            "deaths" -> PlayerStats.printTop(sender, "Top 10 Deaths", PlayerStats.topDeaths(10))
            "kdr" -> PlayerStats.printTop(sender, "Top 10 KDR", PlayerStats.topKdr(10))
            "war" -> {
                if (!phonon.nodes.stats.WarStats.isTracking()) {
                    Message.print(sender, "No war is being tracked right now")
                } else {
                    val title = if (phonon.nodes.stats.WarStats.sessionType() == "skirmish") "Skirmish" else "This war"
                    PlayerStats.printTop(sender, "$title: top 10 kills", phonon.nodes.stats.WarStats.snapshotSortedByKills().take(10))
                }
            }
            "towns", "town" -> PlayerStats.printTop(sender, "Top 10 Towns by KDR", PlayerStats.topTownsKdr(10))
            "nations", "nation" -> PlayerStats.printTop(sender, "Top 10 Nations by KDR", PlayerStats.topNationsKdr(10))
            else -> Message.error(sender, "Usage: /leaderboard <kills|deaths|kdr|towns|nations|war>")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if ((alias.equals("leaderboard", true) || alias.equals("lb", true)) && args.size == 1) {
            return listOf("kills", "deaths", "kdr", "towns", "nations", "war")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 1 && (alias.equals("stats", true) || alias.equals("kdr", true))) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[0].lowercase()) }
        }
        return listOf()
    }
}
