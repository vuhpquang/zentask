package com.vuhpquang.zentask.habits

import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** A habit ready to draw: stats, done days and logged amounts (yyyyMMdd keys). */
data class HabitRow(
    val habit: Habit,
    val stats: HabitStats,
    val schedule: String,
    val doneStamps: Set<Int>,
    /** Logged amount per day for number habits (and 1.0 for done yes/no days). */
    val values: Map<Int, Double>,
    val todayValue: Double,
    val doneToday: Boolean,
    val dueToday: Boolean,
    val archived: Boolean,
)

/** A countdown ready to draw. [daysLeft] < 0 = days since; [years] = age/anniversary count, -1 if not yearly. */
data class CountdownCard(val countdown: Countdown, val dateMillis: Long, val daysLeft: Int, val years: Int)

/** Builds the Habits and Countdown screens from all records in one pass (pure). */
object HabitBoard {
    fun rows(habits: List<Habit>, checkins: List<HabitCheckin>, today: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): List<HabitRow> {
        val byHabit = checkins.filter { !it.deleted }.groupBy { it.habitId }
        val todayStamp = Stamp.of(today)
        return habits.filter { !it.deleted }
            .sortedWith(compareBy({ it.archivedAt != null }, { it.sortOrder }, { it.name }))
            .map { h ->
                val mine = byHabit[h.id].orEmpty()
                val done = mine.filter { it.status == 2 }.mapTo(HashSet()) { it.stamp }
                // A day can carry two check-ins (ZenTask's and TickTick's): done if either is, the larger amount.
                val values = mine.filter { it.value > 0 }.groupBy { it.stamp }.mapValues { (_, cs) -> cs.maxOf { it.value } }
                HabitRow(
                    habit = h,
                    stats = HabitStats.of(h, done.mapNotNullTo(HashSet()) { Stamp.date(it) }, today, zone),
                    schedule = HabitSchedule.describe(h),
                    doneStamps = done,
                    values = values,
                    todayValue = values[todayStamp] ?: 0.0,
                    doneToday = todayStamp in done,
                    dueToday = HabitSchedule.isDue(h, today, zone) && (todayStamp in done || weekLeft(h, done, today)),
                    archived = h.archivedAt != null,
                )
            }
    }

    /** N-times-a-week habits stop being due once this week's target is met (Monday-first weeks). */
    private fun weekLeft(h: Habit, done: Set<Int>, today: LocalDate): Boolean {
        val target = HabitSchedule.weekTarget(h) ?: return true
        val monday = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        val thisWeek = done.count { s -> Stamp.date(s)?.let { it >= monday && it <= today } == true }
        return thisWeek < target
    }

    /** Upcoming soonest first, then past ones most recent first, archived last. */
    fun countdowns(countdowns: List<Countdown>, today: LocalDate, zone: TimeZone): List<CountdownCard> =
        countdowns.filter { !it.deleted }.map { c ->
            val info = CountdownQueries.next(c, today)
            CountdownCard(c, info.date.atStartOfDayIn(zone).toEpochMilliseconds(), info.daysLeft, info.years ?: -1)
        }.sortedWith(
            compareBy<CountdownCard>({ it.countdown.archivedAt != null }, { it.daysLeft < 0 }, { if (it.daysLeft < 0) -it.daysLeft else it.daysLeft }, { it.countdown.name }),
        )
}
