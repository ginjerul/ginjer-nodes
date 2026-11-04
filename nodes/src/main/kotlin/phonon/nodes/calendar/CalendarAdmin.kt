/*
 * /nda calendar: create, edit, remove and schedule events, and send announcements.
 * Permissions: nodes.admin.calendar (all) or nodes.admin.calendar.<action>.
 */

package phonon.nodes.calendar

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import phonon.nodes.Message
import phonon.nodes.mining.MiningBoost
import phonon.nodes.utils.AdminPerms

object CalendarAdmin {
    private val ACTIONS = listOf("add", "edit", "remove", "list", "show", "announce", "announcements", "unannounce")
    private val FIELDS = listOf("title", "desc", "date", "time", "type", "action")
    private val TIME = Regex("""\d{1,2}:\d{2}""")

    fun complete(args: Array<String>): List<String> {
        fun starts(options: List<String>, text: String) = options.filter { it.startsWith(text, ignoreCase = true) }
        return when {
            args.size == 2 -> starts(ACTIONS, args[1])
            args.size == 3 && args[1].equals("add", true) -> starts(listOf("today", "tomorrow", "daily", "weekly:friday", "monthly:1"), args[2])
            args.size == 3 && args[1].lowercase() in listOf("edit", "remove", "show") -> starts(CalendarData.events().map { "${it.id}" }, args[2])
            args.size == 4 && args[1].equals("edit", true) -> starts(FIELDS, args[3])
            args.size == 4 && args[1].equals("add", true) -> starts(EventType.values().map { it.name.lowercase() }, args[3])
            args.size == 5 && args[1].equals("edit", true) && args[3].equals("type", true) -> starts(EventType.values().map { it.name.lowercase() }, args[4])
            else -> emptyList()
        }
    }

    fun handle(sender: CommandSender, args: Array<String>) {
        val sub = args.getOrNull(1)?.lowercase()
        if (sub == null) {
            help(sender)
            return
        }
        if (!AdminPerms.has(sender, "calendar", sub)) {
            Message.error(sender, "You do not have permission for /nodesadmin calendar $sub")
            return
        }
        when (sub) {
            "add" -> add(sender, args)
            "edit" -> edit(sender, args)
            "remove" -> remove(sender, args)
            "list" -> list(sender, args)
            "show" -> show(sender, args)
            "announce" -> announce(sender, args)
            "announcements" -> announcements(sender)
            "unannounce" -> unannounce(sender, args)
            else -> help(sender)
        }
    }

    private fun help(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Calendar] Admin commands:")
        Message.print(sender, "/nda calendar add <date|daily|weekly:FRIDAY|monthly:15> [HH:mm] <type> <title...>")
        Message.print(sender, "  date: 2026-11-07, today, tomorrow. types: ${EventType.values().joinToString(", ") { it.name.lowercase() }}")
        Message.print(sender, "/nda calendar edit <id> title|desc|date|time|type|action <value...>")
        Message.print(sender, "  time none removes the time, action none removes the action.")
        Message.print(sender, "  action war:2h starts a war, boost:1h[:tier] starts the mining boost (needs a time)")
        Message.print(sender, "/nda calendar remove <id> | show <id> | list [all]")
        Message.print(sender, "/nda calendar announce [hours=N] <text...>   (hours=0: until removed)")
        Message.print(sender, "/nda calendar announcements | unannounce <number>")
    }

    private fun idOf(sender: CommandSender, text: String?): CalendarEvent? {
        val event = text?.toIntOrNull()?.let { CalendarData.get(it) }
        if (event == null) {
            Message.error(sender, "No event with that id (see /nda calendar list all)")
        }
        return event
    }

    private fun add(sender: CommandSender, args: Array<String>) {
        if (args.size < 5) {
            Message.error(sender, "Usage: /nda calendar add <date|daily|weekly:DAY|monthly:N> [HH:mm] <type> <title...>")
            return
        }
        val schedule = Schedules.normalize(args[2], Calendar.today())
        if (schedule == null) {
            Message.error(sender, "Invalid date. Use 2026-11-07, today, tomorrow, daily, weekly:FRIDAY or monthly:15")
            return
        }
        var next = 3
        var time: String? = null
        if (TIME.matches(args[next])) {
            if (Schedules.parseTime(args[next]) == null) {
                Message.error(sender, "Invalid time, use HH:mm (24 hour)")
                return
            }
            time = args[next]
            next++
        }
        val type = args.getOrNull(next)?.let { EventType.parse(it) }
        if (type == null || args.size <= next + 1) {
            Message.error(sender, "Give a type (${EventType.values().joinToString(", ") { it.name.lowercase() }}) and a title")
            return
        }
        val title = args.drop(next + 1).joinToString(" ")
        val event = CalendarData.add(
            CalendarEvent(schedule = schedule, title = title, type = type.name, time = time, createdBy = sender.name),
        )
        Message.print(sender, "${ChatColor.GREEN}Added event #${event.id}: ${Calendar.eventLine(event)} ${ChatColor.GRAY}(${Schedules.describe(schedule)})")
    }

    private fun edit(sender: CommandSender, args: Array<String>) {
        if (args.size < 5) {
            Message.error(sender, "Usage: /nda calendar edit <id> title|desc|date|time|type|action <value...>")
            return
        }
        val event = idOf(sender, args[2]) ?: return
        val value = args.drop(4).joinToString(" ")
        when (args[3].lowercase()) {
            "title" -> event.title = value
            "desc", "description" -> event.description = if (value.equals("none", true)) "" else value
            "date", "schedule" -> {
                event.schedule = Schedules.normalize(value, Calendar.today()) ?: run {
                    Message.error(sender, "Invalid date. Use 2026-11-07, today, tomorrow, daily, weekly:FRIDAY or monthly:15")
                    return
                }
            }
            "time" -> {
                if (value.equals("none", true)) {
                    if (event.action != null) {
                        Message.error(sender, "Remove the action first, an action needs a time")
                        return
                    }
                    event.time = null
                } else {
                    if (Schedules.parseTime(value) == null) {
                        Message.error(sender, "Invalid time, use HH:mm (24 hour)")
                        return
                    }
                    event.time = value
                }
            }
            "type" -> {
                val newType = EventType.parse(value)
                if (newType == null) {
                    Message.error(sender, "Types: ${EventType.values().joinToString(", ") { it.name.lowercase() }}")
                    return
                }
                event.type = newType.name
            }
            "action" -> {
                if (value.equals("none", true)) {
                    event.action = null
                } else {
                    if (event.time == null) {
                        Message.error(sender, "Give the event a time first: /nda calendar edit ${event.id} time HH:mm")
                        return
                    }
                    if (!Calendar.validAction(value)) {
                        Message.error(sender, "Action must be war:<duration> or boost:<duration>[:tier], e.g. war:2h or boost:1h:2 (tier 1-${MiningBoost.MAX_TIER})")
                        return
                    }
                    event.action = value.lowercase()
                }
            }
            else -> {
                Message.error(sender, "Fields: ${FIELDS.joinToString(", ")}")
                return
            }
        }
        CalendarData.changed()
        Message.print(sender, "${ChatColor.GREEN}Updated event #${event.id}")
        detail(sender, event)
    }

    private fun remove(sender: CommandSender, args: Array<String>) {
        val event = idOf(sender, args.getOrNull(2)) ?: return
        CalendarData.remove(event.id)
        Message.print(sender, "${ChatColor.GREEN}Removed event #${event.id} (${event.title})")
    }

    private fun detail(sender: CommandSender, e: CalendarEvent) {
        Message.print(sender, "#${e.id} ${Calendar.eventLine(e)} ${ChatColor.GRAY}${Schedules.describe(e.schedule)}")
        if (e.description.isNotBlank()) {
            Message.print(sender, "${ChatColor.GRAY}  ${e.description}")
        }
        if (e.action != null) {
            Message.print(sender, "${ChatColor.GRAY}  action: ${e.action}")
        }
    }

    private fun show(sender: CommandSender, args: Array<String>) {
        detail(sender, idOf(sender, args.getOrNull(2)) ?: return)
    }

    private fun list(sender: CommandSender, args: Array<String>) {
        if (args.getOrNull(2).equals("all", true)) {
            val all = CalendarData.events()
            if (all.isEmpty()) {
                Message.print(sender, "No events yet")
            }
            all.forEach { detail(sender, it) }
        } else {
            Calendar.printUpcoming(sender, 14)
        }
    }

    private fun announce(sender: CommandSender, args: Array<String>) {
        var from = 2
        var hours = Calendar.announcementHours
        val first = args.getOrNull(2)
        if (first != null && first.lowercase().startsWith("hours=")) {
            hours = first.substringAfter("=").toLongOrNull()?.coerceAtLeast(0L) ?: run {
                Message.error(sender, "hours= needs a number")
                return
            }
            from = 3
        }
        if (args.size <= from) {
            Message.error(sender, "Usage: /nda calendar announce [hours=N] <text...>")
            return
        }
        Calendar.announce(ChatColor.translateAlternateColorCodes('&', args.drop(from).joinToString(" ")), hours, sender.name)
    }

    private fun announcements(sender: CommandSender) {
        val all = CalendarData.announcements(System.currentTimeMillis())
        if (all.isEmpty()) {
            Message.print(sender, "No active announcements")
        }
        for ((i, a) in all.withIndex()) {
            Message.print(sender, "${i + 1}. ${a.text} ${ChatColor.GRAY}(by ${a.createdBy}${if (a.expiresAt > 0) ", expires " + java.time.Instant.ofEpochMilli(a.expiresAt) else ""})")
        }
    }

    private fun unannounce(sender: CommandSender, args: Array<String>) {
        val n = args.getOrNull(2)?.toIntOrNull()
        if (n == null || !CalendarData.removeAnnouncement(n - 1)) {
            Message.error(sender, "Give the number from /nda calendar announcements")
            return
        }
        Message.print(sender, "${ChatColor.GREEN}Announcement removed")
    }
}
