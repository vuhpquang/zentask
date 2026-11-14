package com.vuhpquang.zentask.notify

import com.vuhpquang.zentask.habits.CountdownQueries
import com.vuhpquang.zentask.habits.HabitSchedule
import com.vuhpquang.zentask.habits.Stamp
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.tasks.PlannedReminder
import com.vuhpquang.zentask.tasks.ReminderPlanner
import com.vuhpquang.zentask.tasks.isOpen
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Every local notification to schedule (spec §15): task reminders, habit reminders, countdowns and the
 * morning summary, soonest first, at most [limit] (macOS keeps 64 pending per app; snoozes need room).
 * Pure: the platforms store nothing but these, and re-plan whenever the data or the settings change.
 */
object NotificationPlanner {
    private const val MINUTE = 60_000L
    const val TASK_DAYS = 14
    const val HABIT_DAYS = 7
    const val SUMMARY_DAYS = 7
    const val COUNTDOWN_DAYS = 14
    private const val SUMMARY_TITLES = 3
    private val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")

    fun plan(
        prefs: NotificationPrefs,
        tasks: List<Task>,
        dayOf: (Task) -> LocalDate?,
        habits: List<Habit>,
        checkins: List<HabitCheckin>,
        countdowns: List<Countdown>,
        now: Instant,
        zone: TimeZone,
        limit: Int = 60,
    ): List<PlannedReminder> {
        val today = now.toLocalDateTime(zone).date
        val from = now.toEpochMilliseconds()
        val out = mutableListOf<PlannedReminder>()
        if (prefs.tasks) out += ReminderPlanner.upcoming(tasks, now, zone, TASK_DAYS, Int.MAX_VALUE, prefs.allDayMinutes)
        if (prefs.habits) out += habits(habits, checkins, today, zone, from)
        if (prefs.countdowns) out += countdowns(countdowns, prefs, today, zone, from)
        if (prefs.summary) out += summaries(tasks, dayOf, prefs, today, zone, from)
        return out.sortedBy { it.fireAt }.take(limit)
    }

    private fun at(day: LocalDate, minutes: Int, zone: TimeZone): Long =
        day.atStartOfDayIn(zone).toEpochMilliseconds() + minutes * MINUTE

    /** "HH:mm" → minutes after midnight. */
    fun clockMinutes(text: String): Int? = CLOCK.matchEntire(text.trim())?.let { m ->
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    private fun habits(habits: List<Habit>, checkins: List<HabitCheckin>, today: LocalDate, zone: TimeZone, from: Long): List<PlannedReminder> {
        val doneByHabit = checkins.filter { !it.deleted && it.status == 2 }.groupBy({ it.habitId }, { it.stamp })
        val out = mutableListOf<PlannedReminder>()
        for (h in habits) {
            if (h.deleted || h.archivedAt != null || h.reminders.isEmpty()) continue
            val times = h.reminders.mapNotNull(::clockMinutes).distinct()
            val done = doneByHabit[h.id].orEmpty().toSet()
            for (i in 0 until HABIT_DAYS) {
                val day = today.plus(i, DateTimeUnit.DAY)
                val stamp = Stamp.of(day)
                if (stamp in done || !HabitSchedule.isScheduled(h, day, zone, done) || !HabitSchedule.remindsOn(h, day)) continue
                for (m in times) {
                    val fire = at(day, m, zone)
                    if (fire <= from) continue
                    out += PlannedReminder(
                        id = "zt.h.${h.id}.$stamp.$m", taskId = "", title = h.name.ifBlank { "Habit" }, fireAt = fire,
                        kind = PlannedReminder.KIND_HABIT, body = h.encouragement.ifBlank { "Time to check in" }, habitId = h.id, stamp = stamp,
                    )
                }
            }
        }
        return out
    }

    private fun countdowns(countdowns: List<Countdown>, prefs: NotificationPrefs, today: LocalDate, zone: TimeZone, from: Long): List<PlannedReminder> {
        val out = mutableListOf<PlannedReminder>()
        for (c in countdowns) {
            if (c.deleted || c.archivedAt != null) continue
            val next = CountdownQueries.next(c, today)
            val leads = countdownReminders(c, prefs)
            val furthest = leads.maxOfOrNull { it.first } ?: continue
            if (next.daysLeft < 0 || next.daysLeft > COUNTDOWN_DAYS + furthest) continue
            for ((lead, minutes) in leads) {
                val day = next.date.minus(lead, DateTimeUnit.DAY)
                val fire = at(day, minutes, zone)
                if (fire <= from) continue
                val whenText = when (lead) {
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> "In $lead days"
                }
                val years = next.years?.takeIf { it > 0 && lead == 0 }?.let { " · $it years" }.orEmpty()
                out += PlannedReminder(
                    id = "zt.c.${c.id}.${Stamp.of(next.date)}.$lead.$minutes", taskId = "", title = c.name.ifBlank { "Countdown" }, fireAt = fire,
                    kind = PlannedReminder.KIND_COUNTDOWN, body = whenText + years, countdownId = c.id,
                )
            }
        }
        return out
    }

    private val COUNTDOWN_REMINDER = Regex("""^(\d{1,3})@(\d{1,2}:\d{2})$""")

    /** "<days before>@HH:mm" → (days, minutes after midnight), or null when malformed. */
    fun parseCountdownReminder(text: String): Pair<Int, Int>? = COUNTDOWN_REMINDER.matchEntire(text.trim())?.let { m ->
        val minutes = clockMinutes(m.groupValues[2]) ?: return null
        m.groupValues[1].toInt() to minutes
    }

    fun countdownReminder(daysBefore: Int, minutes: Int): String =
        "${daysBefore.coerceAtLeast(0)}@${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    /** "7@09:00" → "1 week before, 09:00"; "0@08:30" → "On the day, 08:30". */
    fun countdownReminderTitle(text: String): String {
        val (days, minutes) = parseCountdownReminder(text) ?: return text
        val time = countdownReminder(0, minutes).substringAfter('@')
        val lead = when {
            days == 0 -> "On the day"
            days == 1 -> "1 day before"
            days % 7 == 0 -> if (days == 7) "1 week before" else "${days / 7} weeks before"
            else -> "$days days before"
        }
        return "$lead, $time"
    }

    /** The defaults from [prefs] in the "<days>@HH:mm" form, for editors to show and start from. */
    fun defaultCountdownReminders(prefs: NotificationPrefs): List<String> =
        listOf(prefs.countdownDaysBefore.coerceAtLeast(0), 0).distinct().map { countdownReminder(it, prefs.countdownMinutes) }

    /** A countdown's reminders as (days before, minutes): its own list, else the defaults from [prefs]. */
    fun countdownReminders(c: Countdown, prefs: NotificationPrefs): List<Pair<Int, Int>> =
        c.reminders?.mapNotNull(::parseCountdownReminder)?.distinct()
            ?: listOf(0, prefs.countdownDaysBefore.coerceAtLeast(0)).distinct().map { it to prefs.countdownMinutes }

    private fun summaries(tasks: List<Task>, dayOf: (Task) -> LocalDate?, prefs: NotificationPrefs, today: LocalDate, zone: TimeZone, from: Long): List<PlannedReminder> {
        val open = tasks.filter { !it.deleted && it.trashedAt == null && it.isOpen && it.parentId == null }
            .mapNotNull { t -> dayOf(t)?.let { t to it } }
        val out = mutableListOf<PlannedReminder>()
        for (i in 0 until SUMMARY_DAYS) {
            val day = today.plus(i, DateTimeUnit.DAY)
            val fire = at(day, prefs.summaryMinutes, zone)
            if (fire <= from) continue
            val due = open.filter { it.second == day }.map { it.first }.sortedWith(compareBy({ !it.isAllDay }, { it.startDate ?: it.dueDate ?: 0 }))
            if (due.isEmpty()) continue
            val overdue = open.count { it.second < day }
            val title = buildString {
                append(if (due.size == 1) "1 task today" else "${due.size} tasks today")
                if (overdue > 0) append(", $overdue overdue")
            }
            val names = due.map { it.title.ifBlank { "Untitled" } }
            val body = if (names.size <= SUMMARY_TITLES) names.joinToString(", ")
            else names.take(SUMMARY_TITLES).joinToString(", ") + " and ${names.size - SUMMARY_TITLES} more"
            out += PlannedReminder(id = "zt.s.${Stamp.of(day)}", taskId = "", title = title, fireAt = fire, kind = PlannedReminder.KIND_SUMMARY, body = body)
        }
        return out
    }
}

/** Snooze choices offered on a notification. */
object Snooze {
    const val FIVE_MINUTES = "5m"
    const val FIFTEEN_MINUTES = "15m"
    const val ONE_HOUR = "1h"
    /** Tomorrow at the all-day reminder time. */
    const val TOMORROW = "tomorrow"

    val ALL = listOf(FIVE_MINUTES, FIFTEEN_MINUTES, ONE_HOUR, TOMORROW)

    fun title(option: String): String = when (option) {
        FIVE_MINUTES -> "Snooze 5 min"
        FIFTEEN_MINUTES -> "Snooze 15 min"
        ONE_HOUR -> "Snooze 1 hour"
        else -> "Tomorrow"
    }

    fun until(option: String, now: Instant, zone: TimeZone, prefs: NotificationPrefs): Long {
        val t = now.toEpochMilliseconds()
        return when (option) {
            FIVE_MINUTES -> t + 5 * 60_000L
            ONE_HOUR -> t + 3_600_000L
            TOMORROW -> now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds() + prefs.allDayMinutes * 60_000L
            else -> t + 15 * 60_000L
        }
    }
}
