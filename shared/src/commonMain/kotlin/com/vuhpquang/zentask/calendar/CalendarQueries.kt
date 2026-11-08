package com.vuhpquang.zentask.calendar

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.habits.CountdownQueries
import com.vuhpquang.zentask.habits.HabitSchedule
import com.vuhpquang.zentask.habits.Stamp
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.ZenJson
import kotlinx.serialization.Serializable
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.tasks.isDone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What the Calendar shows (synced Preference `calendar.view`). Empty [lists] and [tags] = everything;
 * otherwise a task shows when its list or one of its tags is checked. [colorBy]: "priority" or "list".
 */
@Serializable
data class CalendarViewOptions(
    val lists: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val showCompleted: Boolean = true,
    val showHabits: Boolean = true,
    val showCountdowns: Boolean = true,
    val colorBy: String = "priority",
    val showLunar: Boolean = true,
) {
    fun encode(): String = ZenJson.encodeToString(serializer(), this)

    companion object {
        const val KEY = "calendar.view"

        fun parse(json: String): CalendarViewOptions = try {
            ZenJson.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            CalendarViewOptions()
        }
    }
}

/** One thing on the calendar: a Google/iCal event (read-only), a dated task, or a countdown. */
data class CalendarEntry(
    val id: String,
    /** "event", "task", "countdown" or "habit" (id `<habitId>@<yyyyMMdd>`). */
    val kind: String,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val color: String?,
    val done: Boolean = false,
    val location: String = "",
    val priority: Int = 0,
)

/** A timed entry placed in a day: minutes since midnight and its overlap column. */
data class PlacedEntry(val entry: CalendarEntry, val startMinute: Int, val endMinute: Int, val column: Int, val columns: Int)

/** One day column of the calendar. [dayStart] is local midnight (epoch millis). */
data class CalendarDay(val dayStart: Long, val allDay: List<CalendarEntry>, val timed: List<PlacedEntry>)

/**
 * Builds Day/Week/Month content (spec §11): events from visible calendars and dated tasks, all-day ones
 * in the top row, timed ones clipped to each day and laid out in overlap columns. Pure.
 */
object CalendarQueries {
    private const val TASK_MINUTES = 30
    private const val HABIT_MINUTES = 30
    /** A habit reminder time, "19:00". */
    private val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")
    private const val DAY_MINUTES = 24 * 60

    fun days(
        events: List<CalendarEvent>,
        sources: List<CalendarSource>,
        accounts: List<CalendarAccount>,
        tasks: List<Task>,
        first: LocalDate,
        count: Int,
        zone: TimeZone,
        hidden: Set<String>,
        countdowns: List<Countdown> = emptyList(),
        options: CalendarViewOptions = CalendarViewOptions(),
        habits: List<Habit> = emptyList(),
        checkins: List<HabitCheckin> = emptyList(),
        projects: List<Project> = emptyList(),
    ): List<CalendarDay> {
        val listColor = projects.associate { it.id to it.color }
        val wanted = { t: Task ->
            (options.lists.isEmpty() && options.tags.isEmpty()) ||
                t.projectId in options.lists || t.tags.any { tag -> options.tags.any { it.equals(tag, ignoreCase = true) } }
        }
        val liveAccounts = accounts.filter { !it.deleted }.mapTo(HashSet()) { it.id }
        val visible = sources.filter { !it.deleted && it.accountId in liveAccounts && it.id !in hidden }.associateBy { it.id }
        val entries = events.filter { !it.deleted && it.sourceId in visible }.map { e ->
            if (e.isAllDay) {
                // All-day = calendar dates: read them in the event's zone, place them on the same dates here.
                val z = e.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone
                val firstDay = Instant.fromEpochMilliseconds(e.start).toLocalDateTime(z).date
                val endDay = Instant.fromEpochMilliseconds(maxOf(e.end, e.start + 1)).toLocalDateTime(z).date
                val lastExclusive = if (endDay > firstDay) endDay else firstDay.plus(1, DateTimeUnit.DAY)
                CalendarEntry(
                    e.id, "event", e.title, firstDay.atStartOfDayIn(zone).toEpochMilliseconds(), lastExclusive.atStartOfDayIn(zone).toEpochMilliseconds(),
                    true, visible[e.sourceId]?.color, location = e.location,
                )
            } else {
                CalendarEntry(e.id, "event", e.title, e.start, maxOf(e.end, e.start), false, visible[e.sourceId]?.color, location = e.location)
            }
        } + tasks.filter { !it.deleted && it.trashedAt == null && it.status != -1 && wanted(it) && (options.showCompleted || !it.isDone) }
            .mapNotNull { t -> taskEntry(t, zone)?.let { e -> if (options.colorBy == "list") e.copy(color = listColor[t.projectId]) else e } } +
            (if (options.showCountdowns) countdownEntries(countdowns, first, count, zone) else emptyList()) +
            (if (options.showHabits) habitEntries(habits, checkins, first, count, zone).filter { options.showCompleted || !it.done } else emptyList())

        return (0 until count).map { i ->
            val date = first.plus(i, DateTimeUnit.DAY)
            val from = date.atStartOfDayIn(zone).toEpochMilliseconds()
            val to = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
            val allDay = entries.filter { it.allDay && it.start < to && it.end > from }
                .sortedWith(compareBy({ it.kind != "event" }, { it.start }, { it.title }))
            val timed = entries.filter { !it.allDay && it.start < to && (it.end > from || (it.end == it.start && it.start >= from)) }
                .map { e ->
                    val s = ((maxOf(e.start, from) - from) / 60_000).toInt()
                    val end = ((minOf(e.end, to) - from) / 60_000).toInt()
                    Triple(e, s, maxOf(end, minOf(s + 15, DAY_MINUTES)))
                }
            CalendarDay(from, allDay, layout(timed))
        }
    }

    /** Countdowns marked "show in calendar": all-day on their date (every year for yearly ones). */
    private fun countdownEntries(countdowns: List<Countdown>, first: LocalDate, count: Int, zone: TimeZone): List<CalendarEntry> {
        val last = first.plus(count, DateTimeUnit.DAY)
        return countdowns.filter { !it.deleted && it.archivedAt == null && it.showInCalendar }.flatMap { c ->
            val original = Stamp.date(c.date) ?: return@flatMap emptyList()
            val dates = if (c.repeat == "yearly") (first.year..last.year).map { CountdownQueries.onYear(original, it) } else listOf(original)
            dates.filter { it >= first && it < last && it >= original }.map { d ->
                CalendarEntry(
                    "${c.id}@${Stamp.of(d)}", "countdown", c.name, d.atStartOfDayIn(zone).toEpochMilliseconds(),
                    d.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(), true, c.color,
                )
            }
        }
    }

    /** Habits on the days they're due: at their first reminder time (30 min), or all day without one. */
    private fun habitEntries(habits: List<Habit>, checkins: List<HabitCheckin>, first: LocalDate, count: Int, zone: TimeZone): List<CalendarEntry> {
        val done = checkins.filter { !it.deleted && it.status == 2 }.groupBy { it.habitId }.mapValues { (_, cs) -> cs.mapTo(HashSet()) { it.stamp } }
        return habits.filter { !it.deleted && it.archivedAt == null }.flatMap { h ->
            val mine = done[h.id].orEmpty()
            val time = h.reminders.firstNotNullOfOrNull { r -> CLOCK.matchEntire(r.trim())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() } }
            (0 until count).map { first.plus(it, DateTimeUnit.DAY) }.filter { HabitSchedule.isScheduled(h, it, zone, mine) }.map { d ->
                val stamp = Stamp.of(d)
                val midnight = d.atStartOfDayIn(zone).toEpochMilliseconds()
                val start = if (time != null) midnight + time * 60_000L else midnight
                val end = if (time != null) start + HABIT_MINUTES * 60_000L else d.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                CalendarEntry("${h.id}@$stamp", "habit", h.name, start, end, time == null, h.color, stamp in mine)
            }
        }
    }

    private fun taskEntry(t: Task, zone: TimeZone): CalendarEntry? {
        val start = t.startDate ?: t.dueDate ?: return null
        if (t.isAllDay) {
            // All-day tasks are stored as local midnight in their own zone: keep that calendar day.
            val z = t.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone
            val day = Instant.fromEpochMilliseconds(start).toLocalDateTime(z).date
            val s = day.atStartOfDayIn(zone).toEpochMilliseconds()
            return CalendarEntry(t.id, "task", t.title, s, day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(), true, null, t.isDone, priority = t.priority)
        }
        val end = t.dueDate?.takeIf { t.startDate != null && it > start } ?: (start + TASK_MINUTES * 60_000L)
        return CalendarEntry(t.id, "task", t.title, start, end, false, null, t.isDone, priority = t.priority)
    }

    /** Overlap clusters share a column count; each item takes the first free column. */
    private fun layout(items: List<Triple<CalendarEntry, Int, Int>>): List<PlacedEntry> {
        val sorted = items.sortedWith(compareBy({ it.second }, { -(it.third - it.second) }))
        val out = mutableListOf<PlacedEntry>()
        var cluster = mutableListOf<Pair<Triple<CalendarEntry, Int, Int>, Int>>()
        var clusterEnd = -1
        val columnEnds = mutableListOf<Int>()
        fun flush() {
            val n = (cluster.maxOfOrNull { it.second } ?: -1) + 1
            cluster.forEach { (t, col) -> out += PlacedEntry(t.first, t.second, t.third, col, n) }
            cluster = mutableListOf()
            columnEnds.clear()
        }
        for (t in sorted) {
            if (t.second >= clusterEnd) flush()
            val col = columnEnds.indexOfFirst { it <= t.second }.takeIf { it >= 0 } ?: columnEnds.size.also { columnEnds += 0 }
            columnEnds[col] = t.third
            cluster += t to col
            clusterEnd = maxOf(clusterEnd, t.third)
        }
        flush()
        return out
    }
}
