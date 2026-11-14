package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.FocusRecord
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Completed tasks (and focus minutes) on one day. */
data class DayCount(val stamp: Int, val done: Int, val focusMinutes: Int)

/** Completed tasks in one list over the period. */
data class ListCount(val name: String, val color: String?, val done: Int)

/**
 * Productivity over the last [DAYS] days: per-day counts, this week vs last week (Monday weeks), the streak of
 * days with at least one completed task (today counts once it has one; yesterday keeps it alive), the best day
 * and the lists most done in. Pure.
 */
data class ProductivityStats(
    val days: List<DayCount>,
    val thisWeek: Int,
    val lastWeek: Int,
    val streak: Int,
    val bestStreak: Int,
    val best: DayCount?,
    val total: Int,
    val byList: List<ListCount>,
    val focusMinutesThisWeek: Int,
) {
    companion object {
        const val DAYS = 30

        fun of(tasks: List<Task>, projects: List<Project>, focus: List<FocusRecord>, now: Instant, zone: TimeZone): ProductivityStats {
            val today = now.toLocalDateTime(zone).date
            fun dayOf(ms: Long) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date
            val done = tasks.filter { !it.deleted && it.trashedAt == null && it.isDone && it.completedAt != null }
            val doneByDay = done.groupingBy { dayOf(it.completedAt!!) }.eachCount()
            val focusByDay = focus.filter { !it.deleted }.groupBy { dayOf(it.start) }.mapValues { (_, l) -> l.sumOf { it.minutes } }
            val first = today.minus(DAYS - 1, DateTimeUnit.DAY)
            val days = (0 until DAYS).map { i ->
                val d = first.plus(i, DateTimeUnit.DAY)
                DayCount(com.vuhpquang.zentask.habits.Stamp.of(d), doneByDay[d] ?: 0, focusByDay[d] ?: 0)
            }
            val monday = today.minus(today.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
            fun countIn(from: LocalDate, until: LocalDate) = doneByDay.filterKeys { it >= from && it < until }.values.sum()
            val thisWeek = countIn(monday, today.plus(1, DateTimeUnit.DAY))
            val lastWeek = countIn(monday.minus(7, DateTimeUnit.DAY), monday)
            // Current streak: back from today (or yesterday when today has none yet).
            var streak = 0
            var d = if ((doneByDay[today] ?: 0) > 0) today else today.minus(1, DateTimeUnit.DAY)
            while ((doneByDay[d] ?: 0) > 0) { streak++; d = d.minus(1, DateTimeUnit.DAY) }
            var bestStreak = 0
            var run = 0
            var prev: LocalDate? = null
            for (day in doneByDay.keys.sorted()) {
                run = if (prev != null && prev.plus(1, DateTimeUnit.DAY) == day) run + 1 else 1
                bestStreak = maxOf(bestStreak, run)
                prev = day
            }
            val names = projects.associateBy { it.id }
            val inPeriod = done.filter { dayOf(it.completedAt!!) >= first }
            val byList = inPeriod.groupingBy { it.projectId }.eachCount().entries.sortedByDescending { it.value }.take(6).map { (id, n) ->
                val p = names[id]
                ListCount(p?.name ?: "Inbox", p?.color, n)
            }
            val focusWeek = focusByDay.filterKeys { it >= monday && it <= today }.values.sum()
            return ProductivityStats(days, thisWeek, lastWeek, streak, maxOf(bestStreak, streak), days.filter { it.done > 0 }.maxByOrNull { it.done },
                inPeriod.size, byList, focusWeek)
        }
    }
}
