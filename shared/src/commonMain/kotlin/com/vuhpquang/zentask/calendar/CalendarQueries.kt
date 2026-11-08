package com.vuhpquang.zentask.calendar

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.habits.CountdownQueries
import com.vuhpquang.zentask.habits.Stamp
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

/** One thing on the calendar: a Google/iCal event (read-only), a dated task, or a countdown. */
data class CalendarEntry(
    val id: String,
    /** "event", "task" or "countdown". */
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
    ): List<CalendarDay> {
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
        } + tasks.filter { !it.deleted && it.trashedAt == null && it.status != -1 }.mapNotNull { t -> taskEntry(t, zone) } +
            countdownEntries(countdowns, first, count, zone)

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
            dates.filter { it >= first && it < last }.map { d ->
                CalendarEntry(
                    "${c.id}@${Stamp.of(d)}", "countdown", c.name, d.atStartOfDayIn(zone).toEpochMilliseconds(),
                    d.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(), true, c.color,
                )
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
