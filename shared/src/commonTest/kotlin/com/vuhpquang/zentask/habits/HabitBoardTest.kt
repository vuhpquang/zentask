package com.vuhpquang.zentask.habits

import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitBoardTest {
    private val today = LocalDate(2026, 11, 8)

    @Test
    fun rowsCarryStatsAndTodayAndSkipDeleted() {
        val habits = listOf(
            Habit(id = "b", name = "Read", sortOrder = 2),
            Habit(id = "a", name = "Water", type = Habit.TYPE_REAL, goal = 8.0, unit = "cups", sortOrder = 1),
            Habit(id = "z", name = "Gone", deleted = true),
            Habit(id = "c", name = "Old", archivedAt = 5, sortOrder = 0),
        )
        val checkins = listOf(
            HabitCheckin(id = "1", habitId = "b", stamp = 20261107, status = 2),
            HabitCheckin(id = "2", habitId = "b", stamp = 20261108, status = 2),
            HabitCheckin(id = "3", habitId = "a", stamp = 20261108, status = 1, value = 3.0),
            HabitCheckin(id = "4", habitId = "b", stamp = 20261106, status = 2, deleted = true),
        )
        val rows = HabitBoard.rows(habits, checkins, today)
        assertEquals(listOf("a", "b", "c"), rows.map { it.habit.id }, "active by sort order, archived last, deleted gone")
        val read = rows.single { it.habit.id == "b" }
        assertEquals(2, read.stats.currentStreak)
        assertEquals(setOf(20261107, 20261108), read.doneStamps)
        assertTrue(read.doneToday)
        val water = rows.single { it.habit.id == "a" }
        assertEquals(3.0, water.todayValue)
        assertEquals(false, water.doneToday)
        assertEquals(mapOf(20261108 to 3.0), water.values)
        assertTrue(rows.single { it.habit.id == "c" }.archived)
    }

    @Test
    fun countdownCardsSortSoonestFirstAndKeepArchivedLast() {
        val cards = HabitBoard.countdowns(
            listOf(
                Countdown(id = "trip", name = "Trip", date = 20261120),
                Countdown(id = "bday", name = "Mom", date = 19601110, repeat = "yearly", type = "BIRTHDAY"),
                Countdown(id = "past", name = "Moved in", date = 20260101),
                Countdown(id = "old", name = "Old", date = 20261109, archivedAt = 1),
                Countdown(id = "del", name = "Del", date = 20261109, deleted = true),
            ),
            today, TimeZone.of("Asia/Ho_Chi_Minh"),
        )
        assertEquals(listOf("bday", "trip", "past", "old"), cards.map { it.countdown.id })
        val mom = cards.first()
        assertEquals(2, mom.daysLeft)
        assertEquals(66, mom.years)
        assertEquals(-311, cards[2].daysLeft)
        assertEquals(-1, cards[1].years)
    }

    @Test
    fun timesAWeekHabitIsNoLongerDueOnceTheWeekIsDone() {
        val h = Habit(id = "m", name = "Meditate", repeatRule = "RRULE:FREQ=WEEKLY;TT_TIMES=2")
        val sunday = LocalDate(2026, 11, 8)
        fun row(vararg stamps: Int) = HabitBoard.rows(listOf(h), stamps.mapIndexed { i, st -> HabitCheckin(id = "c$i", habitId = "m", stamp = st, status = 2) }, sunday).single()
        assertTrue(row(20261102).dueToday, "1 of 2 this week")
        assertEquals(false, row(20261102, 20261104).dueToday, "target met")
        assertTrue(row(20261102, 20261108).dueToday, "done today still counts as today's habit")
        assertTrue(row(20261026, 20261027).dueToday, "last week's check-ins don't count")
    }
}
