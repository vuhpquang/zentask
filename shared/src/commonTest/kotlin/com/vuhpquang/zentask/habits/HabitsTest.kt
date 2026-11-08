package com.vuhpquang.zentask.habits

import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitsTest {
    private val store = ZenStore(createSqlDriver(null), wall = { 1_000L })
    private val habits = HabitActions(store, wall = { 1_000L })
    private val today = LocalDate(2026, 11, 8) // Sunday
    private fun d(day: Int, month: Int = 11) = LocalDate(2026, month, day)

    @Test
    fun schedules() {
        val daily = Habit(id = "h", repeatRule = "RRULE:FREQ=DAILY;INTERVAL=1")
        assertTrue(HabitSchedule.isDue(daily, today))
        val weekdays = Habit(id = "h", repeatRule = "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertFalse(HabitSchedule.isDue(weekdays, today))
        assertTrue(HabitSchedule.isDue(weekdays, d(9)))
        val times = Habit(id = "h", repeatRule = "RRULE:FREQ=WEEKLY;TT_TIMES=3")
        assertEquals(3, HabitSchedule.weekTarget(times))
        assertTrue(HabitSchedule.isDue(Habit(id = "h", repeatRule = "garbage"), today), "unknown rules count as daily")
        assertTrue(HabitSchedule.describe(weekdays).contains("Mon"))
    }

    @Test
    fun streaksForDailyAndWeekdayHabits() {
        val done = setOf(d(4), d(5), d(6), d(7))
        val daily = Habit(id = "h", repeatRule = "RRULE:FREQ=DAILY")
        val s = HabitStats.of(daily, done, today)
        assertEquals(4, s.currentStreak, "today not done yet does not break the streak")
        assertEquals(4, s.longestStreak)
        assertEquals(4, s.totalDays)
        val weekdays = Habit(id = "h", repeatRule = "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val w = HabitStats.of(weekdays, setOf(d(2), d(4), d(6)), today)
        assertEquals(3, w.currentStreak, "only due days count")
        val broken = HabitStats.of(daily, setOf(d(1), d(2), d(3), d(6), d(7)), today)
        assertEquals(2, broken.currentStreak)
        assertEquals(3, broken.longestStreak)
    }

    @Test
    fun timesPerWeekStreakCountsWeeks() {
        val h = Habit(id = "h", repeatRule = "RRULE:FREQ=WEEKLY;TT_TIMES=2")
        val s = HabitStats.of(h, setOf(d(20, 10), d(22, 10), d(27, 10), d(29, 10), d(3), d(5)), today)
        assertEquals(3, s.currentStreak)
        assertEquals("weeks", s.unit)
    }

    @Test
    fun checkInTogglesBooleanAndAccumulatesNumbers() {
        val yes = habits.create(name = "Read", icon = "📚", color = "#5B5FE9", type = Habit.TYPE_BOOLEAN, goal = 1.0, step = 1.0, unit = "", repeatRule = "RRULE:FREQ=DAILY")
        habits.checkIn(yes.id, today, null)
        assertEquals(setOf(today), habits.doneDays(yes.id))
        habits.checkIn(yes.id, today, null)
        assertTrue(habits.doneDays(yes.id).isEmpty(), "a second click undoes")
        val water = habits.create(name = "Water", icon = "💧", color = null, type = Habit.TYPE_REAL, goal = 8.0, step = 2.0, unit = "glasses", repeatRule = "RRULE:FREQ=DAILY")
        repeat(3) { habits.checkIn(water.id, today, null) }
        assertEquals(6.0, habits.checkin(water.id, today)?.value)
        assertEquals(1, habits.checkin(water.id, today)?.status)
        habits.checkIn(water.id, today, 2.0)
        assertEquals(2, habits.checkin(water.id, today)?.status)
    }

    @Test
    fun importedCheckinForTheDayIsReused() {
        val h = habits.create(name = "Run", icon = null, color = null, type = Habit.TYPE_BOOLEAN, goal = 1.0, step = 1.0, unit = "", repeatRule = "RRULE:FREQ=DAILY")
        store.put(EntityTypes.habitCheckin, HabitCheckin(id = "tt_k1", habitId = h.id, stamp = 20261108, status = 2, value = 1.0))
        habits.checkIn(h.id, today, null)
        assertEquals(listOf("tt_k1"), store.list(EntityTypes.habitCheckin).filter { it.habitId == h.id }.map { it.id })
        assertEquals(0, store.get(EntityTypes.habitCheckin, "tt_k1")!!.status)
    }

    @Test
    fun deterministicCheckinIdsMergeAcrossDevices() {
        val h = habits.create(name = "Run", icon = null, color = null, type = Habit.TYPE_BOOLEAN, goal = 1.0, step = 1.0, unit = "", repeatRule = "RRULE:FREQ=DAILY")
        habits.checkIn(h.id, today, null)
        assertEquals("chk_${h.id}_20261108", store.list(EntityTypes.habitCheckin).single().id)
    }

    @Test
    fun archiveAndDelete() {
        val h = habits.create(name = "Old", icon = null, color = null, type = Habit.TYPE_BOOLEAN, goal = 1.0, step = 1.0, unit = "", repeatRule = "RRULE:FREQ=DAILY")
        habits.checkIn(h.id, today, null)
        habits.archive(h.id)
        assertTrue(store.get(EntityTypes.habit, h.id)!!.archivedAt != null)
        habits.unarchive(h.id)
        assertNull(store.get(EntityTypes.habit, h.id)!!.archivedAt)
        habits.delete(h.id)
        assertTrue(store.get(EntityTypes.habit, h.id)!!.deleted)
        assertTrue(store.list(EntityTypes.habitCheckin).isEmpty())
    }

    @Test
    fun countdownNextOccurrence() {
        val birthday = Countdown(id = "c", date = 19900215, repeat = "yearly", type = "BIRTHDAY")
        val next = CountdownQueries.next(birthday, today)
        assertEquals(LocalDate(2027, 2, 15), next.date)
        assertEquals(99, next.daysLeft)
        assertEquals(37, next.years)
        val leap = CountdownQueries.next(Countdown(id = "l", date = 20000229, repeat = "yearly"), today)
        assertEquals(LocalDate(2027, 2, 28), leap.date)
        val past = CountdownQueries.next(Countdown(id = "p", date = 20261101), today)
        assertEquals(-7, past.daysLeft)
        val todayOne = CountdownQueries.next(Countdown(id = "t", date = 20201108, repeat = "yearly"), today)
        assertEquals(0, todayOne.daysLeft)
        assertEquals(6, todayOne.years)
    }
}
