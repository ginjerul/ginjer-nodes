/*
 * /calendar: the server calendar. No arguments opens the month menu, "today" and "upcoming"
 * print text.
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.calendar.Calendar
import phonon.nodes.calendar.CalendarMenu

class CalendarCommand :
    CommandExecutor,
    TabCompleter {
    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<String>): Boolean {
        if (!Calendar.isEnabled()) {
            Message.error(sender, "The calendar is turned off")
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            "help" -> help(sender)
            "today" -> {
                Calendar.printDay(sender, Calendar.today())
                Calendar.printAnnouncements(sender)
            }
            "upcoming", "week" -> Calendar.printUpcoming(sender, 7)
            else -> {
                if (sender is Player) {
                    CalendarMenu.open(sender)
                } else {
                    Calendar.printUpcoming(sender, 7)
                }
            }
        }
        return true
    }

    private fun help(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Calendar commands:")
        Message.print(sender, "/calendar${ChatColor.WHITE}: Open the server calendar")
        Message.print(sender, "/calendar today${ChatColor.WHITE}: Today's events and announcements")
        Message.print(sender, "/calendar upcoming${ChatColor.WHITE}: Events in the next 7 days")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> = if (args.size == 1) listOf("today", "upcoming", "help").filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()
}
