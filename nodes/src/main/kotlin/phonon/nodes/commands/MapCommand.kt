/*
 * /map: the player's discovered points of interest. /map title <name|none> picks the title
 * shown in chat from the ones earned by exploring.
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.poi.PoiData
import phonon.nodes.poi.PoiMenu
import phonon.nodes.poi.Pois

class MapCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            Message.error(sender, "Only players can use /map")
            return true
        }
        if (!Pois.isEnabled()) {
            Message.error(sender, "Exploration is turned off")
            return true
        }
        if (args.isEmpty()) {
            PoiMenu.open(sender)
            return true
        }
        if (!args[0].equals("title", ignoreCase = true)) {
            Message.print(sender, "${ChatColor.BOLD}[Nodes] Map commands:")
            Message.print(sender, "/map${ChatColor.WHITE}: Places you have discovered and what is left to find")
            Message.print(sender, "/map title <name>${ChatColor.WHITE}: Show an earned title in chat")
            Message.print(sender, "/map title none${ChatColor.WHITE}: Remove your title")
            return true
        }
        val progress = PoiData.progress(sender.uniqueId.toString())
        if (args.size < 2) {
            Message.print(sender, "Titles: ${if (progress.titles.isEmpty()) "none earned yet" else progress.titles.joinToString(", ") { Pois.colour(it) + ChatColor.RESET }}")
            Message.print(sender, "Pick one with /map title <name>, or /map title none")
            return true
        }
        val wanted = args.drop(1).joinToString(" ")
        if (wanted.equals("none", ignoreCase = true)) {
            progress.selectedTitle = null
            PoiData.saveDiscoveries()
            Message.print(sender, "Title removed")
            return true
        }
        val match = progress.titles.firstOrNull { ChatColor.stripColor(Pois.colour(it))!!.equals(ChatColor.stripColor(Pois.colour(wanted)), ignoreCase = true) }
        if (match == null) {
            Message.error(sender, "You have not earned that title")
            return true
        }
        progress.selectedTitle = match
        PoiData.saveDiscoveries()
        Message.print(sender, "Title set to ${Pois.colour(match)}")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (sender !is Player) {
            return emptyList()
        }
        if (args.size == 1) {
            return listOf("title").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("title", ignoreCase = true)) {
            val titles = PoiData.peek(sender.uniqueId.toString())?.titles ?: emptyList()
            return (titles.map { ChatColor.stripColor(Pois.colour(it)) ?: it } + "none").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
