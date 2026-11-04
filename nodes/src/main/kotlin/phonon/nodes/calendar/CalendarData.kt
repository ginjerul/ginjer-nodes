/*
 * Server calendar data: scheduled events and announcements, saved in plugins/nodes/calendar.json.
 * An event happens on one date or repeats (daily, weekly:FRIDAY, monthly:15). An event with a
 * time and an action (war:2h or boost:1h:2) also starts that action at its time.
 */

package phonon.nodes.calendar

import com.google.gson.GsonBuilder
import phonon.nodes.Nodes
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class EventType(val label: String) {
    WAR("War"),
    COMPETITION("Competition"),
    COMMUNITY("Community"),
    BOOST("Boost"),
    SPECIAL("Special"),
    OTHER("Other"),
    ;

    companion object {
        fun parse(text: String): EventType? = values().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

class CalendarEvent(
    var id: Int = 0,
    // yyyy-MM-dd, daily, weekly:FRIDAY or monthly:15
    var schedule: String = "",
    var title: String = "",
    var description: String = "",
    var type: String = EventType.OTHER.name,
    // HH:mm, optional
    var time: String? = null,
    // war:<duration> or boost:<duration>[:tier], needs a time
    var action: String? = null,
    var createdBy: String = "",
) {
    fun eventType(): EventType = EventType.parse(type) ?: EventType.OTHER

    fun localTime(): LocalTime? = time?.let { Schedules.parseTime(it) }

    fun occursOn(date: LocalDate): Boolean = Schedules.occursOn(schedule, date)
}

class CalendarAnnouncement(
    var text: String = "",
    var createdAt: Long = 0L,
    // 0 = never
    var expiresAt: Long = 0L,
    var createdBy: String = "",
)

private class CalendarFile(
    var nextId: Int = 1,
    var events: MutableList<CalendarEvent> = mutableListOf(),
    var announcements: MutableList<CalendarAnnouncement> = mutableListOf(),
    // what already fired and on which day, so nothing fires twice
    var fired: MutableMap<String, String> = mutableMapOf(),
)

object Schedules {
    fun parseTime(text: String): LocalTime? = try {
        val parts = text.trim().split(":")
        if (parts.size != 2) null else LocalTime.of(parts[0].toInt(), parts[1].toInt())
    } catch (_: Exception) {
        null
    }

    // returns the cleaned schedule text, or null when it is not valid
    fun normalize(text: String, today: LocalDate): String? {
        val t = text.trim().lowercase()
        return when {
            t == "today" -> today.toString()
            t == "tomorrow" -> today.plusDays(1).toString()
            t == "daily" -> "daily"
            t.startsWith("weekly:") -> dayOfWeek(t.substringAfter(":"))?.let { "weekly:${it.name}" }
            t.startsWith("monthly:") -> t.substringAfter(":").toIntOrNull()?.takeIf { it in 1..31 }?.let { "monthly:$it" }
            else -> try {
                LocalDate.parse(t).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun dayOfWeek(text: String): DayOfWeek? = DayOfWeek.values().firstOrNull {
        it.name.equals(text, ignoreCase = true) || it.name.substring(0, 3).equals(text, ignoreCase = true)
    }

    fun occursOn(schedule: String, date: LocalDate): Boolean = when {
        schedule == "daily" -> true
        schedule.startsWith("weekly:") -> date.dayOfWeek.name == schedule.substringAfter(":")
        schedule.startsWith("monthly:") -> {
            val day = schedule.substringAfter(":").toIntOrNull() ?: 0
            date.dayOfMonth == minOf(day, date.lengthOfMonth())
        }
        else -> schedule == date.toString()
    }

    fun describe(schedule: String): String = when {
        schedule == "daily" -> "every day"
        schedule.startsWith("weekly:") -> "every ${schedule.substringAfter(":").lowercase().replaceFirstChar { it.uppercase() }}"
        schedule.startsWith("monthly:") -> "day ${schedule.substringAfter(":")} of every month"
        else -> schedule
    }
}

object CalendarData {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/calendar.json")
    private var file = CalendarFile()

    fun load() {
        file = CalendarFile()
        if (!Files.exists(path)) {
            return
        }
        try {
            val text = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            gson.fromJson(text, CalendarFile::class.java)?.let { file = it }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Calendar] Could not read calendar.json: ${e.message}")
        }
    }

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("calendar.tmp")
            Files.write(tmp, gson.toJson(file).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Nodes.logger?.severe("[Calendar] Could not save calendar.json: ${e.message}")
        }
    }

    fun events(): List<CalendarEvent> = file.events.sortedBy { it.id }

    fun get(id: Int): CalendarEvent? = file.events.firstOrNull { it.id == id }

    fun eventsOn(date: LocalDate): List<CalendarEvent> = file.events.filter { it.occursOn(date) }.sortedWith(compareBy({ it.localTime() ?: LocalTime.MIN }, { it.id }))

    fun add(event: CalendarEvent): CalendarEvent {
        event.id = file.nextId++
        file.events.add(event)
        save()
        return event
    }

    fun changed() = save()

    fun remove(id: Int): Boolean {
        val removed = file.events.removeIf { it.id == id }
        if (removed) {
            file.fired.keys.removeIf { it == "$id" || it == "$id:r" }
            save()
        }
        return removed
    }

    fun announcements(now: Long): List<CalendarAnnouncement> {
        val before = file.announcements.size
        file.announcements.removeIf { it.expiresAt in 1..now }
        if (file.announcements.size != before) {
            save()
        }
        return file.announcements.toList()
    }

    fun addAnnouncement(a: CalendarAnnouncement) {
        file.announcements.add(a)
        save()
    }

    fun removeAnnouncement(index: Int): Boolean {
        if (index < 0 || index >= file.announcements.size) {
            return false
        }
        file.announcements.removeAt(index)
        save()
        return true
    }

    fun firedOn(key: String): String? = file.fired[key]

    fun markFired(key: String, day: LocalDate) {
        file.fired[key] = day.toString()
        save()
    }
}
