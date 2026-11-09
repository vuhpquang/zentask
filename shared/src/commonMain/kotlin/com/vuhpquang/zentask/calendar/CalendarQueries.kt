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
import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.FocusRecord
import com.vuhpquang.zentask.tasks.Recurrence
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
    /** Checklist items that have their own date. */
    val showChecklist: Boolean = false,
    /** Later occurrences of repeating tasks (read-only copies on their dates). */
    val showRepeats: Boolean = true,
    /** Finished focus sessions as blocks at the time they ran. */
    val showFocus: Boolean = false,
    /** One colour for every habit ("#RRGGBB"), or "" for each habit's own colour. */
    val habitColor: String = "",
    /** One colour for every countdown, or "" for each countdown's own colour. */
    val countdownColor: String = "",
    /** "classic" (solid blocks) or "modern" (tinted with a coloured edge). */
    val itemStyle: String = "classic",
    /** Small kind icons (calendar, habit, countdown, focus, repeat) on entries. */
    val showIcons: Boolean = true,
    /** Year view shades days by how many items they have. */
    val yearHeatmap: Boolean = true,
    /** A second time column in Day/Week ("Asia/Tokyo"), or "" for none. */
    val extraZone: String = "",
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
    /** For "repeat" and "checklist" entries: the task they belong to (opens on click). */
    val taskId: String? = null,
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
    /** Safety cap on generated repeat occurrences per task (a daily rule over a year view is 366). */
    private const val MAX_REPEATS = 400

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
        checklist: List<ChecklistItem> = emptyList(),
        focus: List<FocusRecord> = emptyList(),
    ): List<CalendarDay> {
        val listColor = projects.associate { it.id to it.color }
        // Tasks far outside the window can't show; skip them before any time-zone work (margin: all-day zones).
        val windowFrom = first.atStartOfDayIn(zone).toEpochMilliseconds() - 2 * 86_400_000L
        val windowTo = first.plus(count, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() + 2 * 86_400_000L
        val zones = HashMap<String, TimeZone>()
        val zoneOf = { id: String? -> id?.let { zones.getOrPut(it) { runCatching { TimeZone.of(it) }.getOrNull() ?: zone } } ?: zone }
        val wanted = { t: Task ->
            (options.lists.isEmpty() && options.tags.isEmpty()) ||
                t.projectId in options.lists || t.tags.any { tag -> options.tags.any { it.equals(tag, ignoreCase = true) } }
        }
        val liveAccounts = accounts.filter { !it.deleted }.mapTo(HashSet()) { it.id }
        val visible = sources.filter { !it.deleted && it.accountId in liveAccounts && it.id !in hidden }.associateBy { it.id }
        val entries: List<CalendarEntry> = events.filter { !it.deleted && it.sourceId in visible }.map { e ->
            if (e.isAllDay) {
                // All-day = calendar dates: read them in the event's zone, place them on the same dates here.
                val z = zoneOf(e.timeZone)
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
        }
        val live = { t: Task -> !t.deleted && t.trashedAt == null && t.status != -1 && wanted(t) }
        val colour = { t: Task, e: CalendarEntry -> if (options.colorBy == "list") e.copy(color = listColor[t.projectId]) else e }
        val taskEntries = tasks.filter { t ->
            val at = t.startDate ?: t.dueDate
            // Overlap, not start-in-window: a span that began days earlier still covers the shown days.
            at != null && at <= windowTo && maxOf(t.dueDate ?: at, at) >= windowFrom && live(t) && (options.showCompleted || !t.isDone)
        }.mapNotNull { t -> taskEntry(t, zone, zoneOf(t.timeZone))?.let { colour(t, it) } }
        val repeatEntries = if (!options.showRepeats) emptyList() else tasks.filter { live(it) && !it.isDone && !it.repeatRule.isNullOrBlank() }
            .flatMap { t -> repeatEntries(t, zone, zoneOf(t.timeZone), windowFrom, windowTo).map { colour(t, it) } }
        val checklistEntries = if (!options.showChecklist) emptyList() else {
            val parents = tasks.filter { live(it) }.associateBy { it.id }
            checklist.filter { !it.deleted && it.startDate != null && it.startDate in windowFrom..windowTo && (options.showCompleted || it.status == 0) }
                .mapNotNull { item ->
                    val t = parents[item.taskId] ?: return@mapNotNull null
                    val start = item.startDate!!
                    val e = if (item.isAllDay) {
                        val day = Instant.fromEpochMilliseconds(start).toLocalDateTime(zoneOf(t.timeZone)).date
                        CalendarEntry(item.id, "checklist", item.title, day.atStartOfDayIn(zone).toEpochMilliseconds(),
                            day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds(), true, null, item.status != 0, priority = t.priority, taskId = t.id)
                    } else {
                        CalendarEntry(item.id, "checklist", item.title, start, start + TASK_MINUTES * 60_000L, false, null, item.status != 0, priority = t.priority, taskId = t.id)
                    }
                    colour(t, e)
                }
        }
        val focusEntries = if (!options.showFocus) emptyList() else {
            val titles = tasks.associate { it.id to it.title }
            focus.filter { !it.deleted && it.end > it.start && it.start <= windowTo && it.end >= windowFrom }.map { f ->
                CalendarEntry(f.id, "focus", f.taskId?.let { titles[it] }?.takeIf { it.isNotBlank() } ?: "Focus", f.start, f.end, false, null, true, taskId = f.taskId)
            }
        }
        val all = entries + taskEntries + repeatEntries + checklistEntries + focusEntries +
            (if (options.showCountdowns) countdownEntries(countdowns, first, count, zone).map { if (options.countdownColor.isNotEmpty()) it.copy(color = options.countdownColor) else it } else emptyList()) +
            (if (options.showHabits) habitEntries(habits, checkins, first, count, zone).filter { options.showCompleted || !it.done }
                .map { if (options.habitColor.isNotEmpty()) it.copy(color = options.habitColor) else it } else emptyList())

        return (0 until count).map { i ->
            val date = first.plus(i, DateTimeUnit.DAY)
            val from = date.atStartOfDayIn(zone).toEpochMilliseconds()
            val to = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
            val allDay = all.filter { it.allDay && it.start < to && it.end > from }
                .sortedWith(compareBy({ it.kind != "event" }, { it.start }, { it.title }))
            val timed = all.filter { !it.allDay && it.start < to && (it.end > from || (it.end == it.start && it.start >= from)) }
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

    private fun taskEntry(t: Task, zone: TimeZone, own: TimeZone): CalendarEntry? {
        val start = t.startDate ?: t.dueDate ?: return null
        if (t.isAllDay) {
            // All-day tasks are stored as local midnight in their own zone: keep that calendar day.
            val day = Instant.fromEpochMilliseconds(start).toLocalDateTime(own).date
            val s = day.atStartOfDayIn(zone).toEpochMilliseconds()
            // A multi-day all-day task ends at the midnight after its last day (TickTick's convention).
            val endDay = t.dueDate?.takeIf { t.startDate != null && it > start }?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(own).date }
                ?.takeIf { it > day } ?: day.plus(1, DateTimeUnit.DAY)
            return CalendarEntry(t.id, "task", t.title, s, endDay.atStartOfDayIn(zone).toEpochMilliseconds(), true, null, t.isDone, priority = t.priority)
        }
        val end = t.dueDate?.takeIf { t.startDate != null && it > start } ?: (start + TASK_MINUTES * 60_000L)
        return CalendarEntry(t.id, "task", t.title, start, end, false, null, t.isDone, priority = t.priority)
    }

    /**
     * Later occurrences of a repeating task inside the window (the task itself is its first occurrence).
     * Repeats counted from completion have no fixed future dates, so they show none.
     */
    private fun repeatEntries(t: Task, zone: TimeZone, own: TimeZone, windowFrom: Long, windowTo: Long): List<CalendarEntry> {
        if (t.repeatFrom == "completion" || t.repeatFrom == "1") return emptyList()
        val rule = t.repeatRule ?: return emptyList()
        val base = taskEntry(t, zone, own) ?: return emptyList()
        val first = t.startDate ?: t.dueDate ?: return emptyList()
        val length = base.end - base.start
        val out = mutableListOf<CalendarEntry>()
        var at = Instant.fromEpochMilliseconds(first)
        for (i in 0 until MAX_REPEATS) {
            val next = runCatching { Recurrence.next(rule, at, own, i) }.getOrNull() ?: break
            if (next <= at) break
            at = next
            val ms = next.toEpochMilliseconds()
            if (ms > windowTo) break
            val start = if (t.isAllDay) next.toLocalDateTime(own).date.atStartOfDayIn(zone).toEpochMilliseconds() else ms
            if (start + length < windowFrom) continue
            out += base.copy(id = "${t.id}@$start", kind = "repeat", start = start, end = start + length, done = false, taskId = t.id)
        }
        return out
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
