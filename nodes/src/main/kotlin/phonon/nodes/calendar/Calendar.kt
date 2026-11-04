/*
 * The calendar's clock: announces the day's events, starts events at their time (reminder first),
 * runs their war or mining boost action and shows the day to players who join.
 * Settings are under `calendar:` in the config.
 */

package phonon.nodes.calendar

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningBoost
import phonon.nodes.war.WarTimer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Calendar {
    private var enabled = true
    private var zone: ZoneId = ZoneId.systemDefault()
    private var dailyAnnounce: LocalTime? = LocalTime.of(12, 0)
    private var showOnJoin = true
    private var remindMinutes = 10L
    private var catchUpMinutes = 30L
    internal var announcementHours = 24L
    private var task: BukkitTask? = null

    private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

    fun loadConfig(config: ConfigurationSection) {
        val sec = config.getConfigurationSection("calendar")
        enabled = sec?.getBoolean("enabled", true) ?: true
        zone = try {
            val name = sec?.getString("timezone", "") ?: ""
            if (name.isBlank()) ZoneId.systemDefault() else ZoneId.of(name.trim())
        } catch (_: Exception) {
            Nodes.logger?.warning("[Calendar] Unknown calendar.timezone, using the server's")
            ZoneId.systemDefault()
        }
        val daily = sec?.getString("dailyAnnounceTime", "12:00") ?: "12:00"
        dailyAnnounce = if (daily.isBlank()) null else Schedules.parseTime(daily)
        showOnJoin = sec?.getBoolean("showOnJoin", true) ?: true
        remindMinutes = (sec?.getLong("remindMinutes", 10L) ?: 10L).coerceIn(0L, 1440L)
        catchUpMinutes = (sec?.getLong("catchUpMinutes", 30L) ?: 30L).coerceIn(0L, 1440L)
        announcementHours = (sec?.getLong("announcementHours", 24L) ?: 24L).coerceIn(0L, 24L * 365L)
    }

    fun isEnabled(): Boolean = enabled

    fun now(): ZonedDateTime = ZonedDateTime.now(zone)

    fun today(): LocalDate = now().toLocalDate()

    fun start(plugin: Plugin) {
        task?.cancel()
        task = null
        CalendarData.load()
        if (!enabled) {
            return
        }
        Bukkit.getPluginManager().registerEvents(CalendarListener(), plugin)
        Bukkit.getPluginManager().registerEvents(CalendarMenuListener(), plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 200L, 400L)
    }

    // ===================================
    // text
    // ===================================
    fun dateText(date: LocalDate): String = date.format(dateFormat)

    fun eventLine(e: CalendarEvent): String {
        val time = e.time?.let { "$it " } ?: ""
        return "${ChatColor.GOLD}$time${ChatColor.YELLOW}[${e.eventType().label}] ${ChatColor.WHITE}${e.title}"
    }

    // prints one day for a player or console: the events with descriptions
    fun printDay(sender: CommandSender, date: LocalDate) {
        Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}${dateText(date)}")
        val events = CalendarData.eventsOn(date)
        if (events.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}Nothing planned")
        }
        for (e in events) {
            Message.print(sender, eventLine(e))
            if (e.description.isNotBlank()) {
                Message.print(sender, "${ChatColor.GRAY}  ${e.description}")
            }
        }
    }

    fun printAnnouncements(sender: CommandSender) {
        for (a in CalendarData.announcements(System.currentTimeMillis())) {
            Message.print(sender, "${ChatColor.RED}${ChatColor.BOLD}Announcement: ${ChatColor.WHITE}${a.text}")
        }
    }

    // the next `days` days that have events
    fun printUpcoming(sender: CommandSender, days: Int) {
        val start = today()
        var any = false
        for (i in 0 until days) {
            val date = start.plusDays(i.toLong())
            val events = CalendarData.eventsOn(date)
            if (events.isEmpty()) {
                continue
            }
            any = true
            Message.print(sender, "${ChatColor.GOLD}${ChatColor.BOLD}${if (i == 0) "Today" else dateText(date)}")
            for (e in events) {
                Message.print(sender, "  " + eventLine(e))
            }
        }
        if (!any) {
            Message.print(sender, "${ChatColor.GRAY}Nothing planned in the next $days days")
        }
    }

    // ===================================
    // clock
    // ===================================
    private fun tick() {
        try {
            val now = now()
            val today = now.toLocalDate()
            val events = CalendarData.eventsOn(today)

            val daily = dailyAnnounce
            if (daily != null && !now.toLocalTime().isBefore(daily) && CalendarData.firedOn("daily") != today.toString()) {
                CalendarData.markFired("daily", today)
                if (events.isNotEmpty()) {
                    Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Calendar] Today: ${ChatColor.RESET}${events.joinToString("${ChatColor.GRAY}, ") { eventLine(it) }}")
                }
            }

            for (e in events) {
                val time = e.localTime() ?: continue
                val startAt = ZonedDateTime.of(today, time, zone)
                val key = "${e.id}"
                val firedToday = CalendarData.firedOn(key) == today.toString()

                if (remindMinutes > 0 && !firedToday && CalendarData.firedOn("$key:r") != today.toString()) {
                    val remindAt = startAt.minusMinutes(remindMinutes)
                    if (!now.isBefore(remindAt) && now.isBefore(startAt)) {
                        CalendarData.markFired("$key:r", today)
                        Message.broadcast("${ChatColor.GOLD}[Calendar] In $remindMinutes minutes: ${eventLine(e)}")
                    }
                }

                if (!firedToday && !now.isBefore(startAt)) {
                    CalendarData.markFired(key, today)
                    if (now.isBefore(startAt.plusMinutes(catchUpMinutes))) {
                        begin(e)
                    }
                }
            }
        } catch (ex: Exception) {
            Nodes.logger?.warning("[Calendar] Clock stopped for one cycle: ${ex.message}")
        }
    }

    // an event reaches its start time: announce it and run its action
    private fun begin(e: CalendarEvent) {
        Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Calendar] Now: ${ChatColor.RESET}${eventLine(e)}")
        if (e.description.isNotBlank()) {
            Message.broadcast("${ChatColor.GRAY}${e.description}")
        }
        phonon.nodes.util.DiscordRelay.war("**[Calendar]** ${e.title}" + if (e.description.isNotBlank()) " - ${e.description}" else "")
        runAction(e)
    }

    private fun runAction(e: CalendarEvent) {
        val parts = (e.action ?: return).split(":")
        val duration = parts.getOrNull(1)?.let { MiningBoost.parseDurationMs(it) }
        when (parts[0].lowercase()) {
            "war" -> {
                if (duration == null) {
                    return
                }
                if (Nodes.war.enabled) {
                    Nodes.logger?.info("[Calendar] ${e.title}: war is already on, left as it is")
                    return
                }
                Nodes.enableWar(true, false, true)
                phonon.nodes.stats.WarStats.startWarSession(fullWar = true)
                WarTimer.scheduleDisable(duration, "War time expired")
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled by the calendar")
            }
            "boost" -> {
                if (duration == null) {
                    return
                }
                val tier = parts.getOrNull(2)?.toIntOrNull()
                if (tier != null && tier in 1..MiningBoost.MAX_TIER) {
                    MiningBoost.setGlobal(tier, duration, "Calendar")
                } else {
                    MiningBoost.bumpGlobal(duration, "Calendar")
                }
            }
        }
    }

    // sends an announcement to everyone now
    fun announce(text: String, hours: Long, by: String) {
        val now = System.currentTimeMillis()
        CalendarData.addAnnouncement(
            CalendarAnnouncement(text, now, if (hours > 0) now + hours * 3_600_000L else 0L, by),
        )
        Message.broadcast("${ChatColor.RED}${ChatColor.BOLD}[Announcement] ${ChatColor.RESET}$text")
        phonon.nodes.util.DiscordRelay.war("**[Announcement]** $text")
    }

    // valid war:2h / boost:1h[:tier]
    fun validAction(text: String): Boolean {
        val parts = text.split(":")
        if (parts[0].lowercase() != "war" && parts[0].lowercase() != "boost") {
            return false
        }
        return parts.getOrNull(1)?.let { MiningBoost.parseDurationMs(it) } != null
    }

    internal fun showToJoiner(player: Player) {
        if (!showOnJoin) {
            return
        }
        val events = CalendarData.eventsOn(today())
        val announcements = CalendarData.announcements(System.currentTimeMillis())
        if (events.isEmpty() && announcements.isEmpty()) {
            return
        }
        if (events.isNotEmpty()) {
            printDay(player, today())
        }
        printAnnouncements(player)
        Message.print(player, "${ChatColor.GRAY}Use /calendar to see the coming days")
    }
}

class CalendarListener : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(
            Nodes.plugin!!,
            Runnable {
                if (player.isOnline) {
                    Calendar.showToJoiner(player)
                }
            },
            60L,
        )
    }
}
