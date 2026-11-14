package com.vuhpquang.zentask.notify

import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.tasks.PlannedReminder
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPlannerTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    // Sunday 8 Nov 2026, 10:00.
    private val now = LocalDateTime.parse("2026-11-08T10:00").toInstant(zone)
    private fun day(t: Task): LocalDate? = (t.startDate ?: t.dueDate)?.let { kotlin.time.Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }

    private fun plan(
        prefs: NotificationPrefs = NotificationPrefs(),
        tasks: List<Task> = emptyList(),
        habits: List<Habit> = emptyList(),
        checkins: List<HabitCheckin> = emptyList(),
        countdowns: List<Countdown> = emptyList(),
        limit: Int = 60,
    ) = NotificationPlanner.plan(prefs, tasks, ::day, habits, checkins, countdowns, now, zone, limit)

    @Test
    fun taskRemindersUseTheAllDayTime() {
        val t = Task(id = "a", title = "Pay rent", dueDate = ms("2026-11-09T00:00"), isAllDay = true, timeZone = zone.id, reminders = listOf("TRIGGER:PT0S"))
        val r = plan(NotificationPrefs(summary = false, allDayMinutes = 7 * 60 + 30), tasks = listOf(t))
        assertEquals(listOf(ms("2026-11-09T07:30")), r.map { it.fireAt })
        assertEquals(PlannedReminder.KIND_TASK, r.single().kind)
        assertEquals("a", r.single().taskId)
    }

    @Test
    fun allDayNineOClockTriggersFollowTheSettingButOtherTimesStay() {
        val t = Task(
            id = "a", title = "Trip", dueDate = ms("2026-11-10T00:00"), isAllDay = true, timeZone = zone.id,
            reminders = listOf("TRIGGER:PT9H", "TRIGGER:-PT15H", "TRIGGER:P0DT17H50M0S"),
        )
        val r = plan(NotificationPrefs(summary = false, allDayMinutes = 7 * 60), tasks = listOf(t))
        assertEquals(listOf(ms("2026-11-09T07:00"), ms("2026-11-10T07:00"), ms("2026-11-10T17:50")), r.map { it.fireAt })
    }

    @Test
    fun weeklyHabitsRemindFromTheirStartDayUntilDone() {
        // Once a week, reminders from Friday (5). Today is Sunday 8th; the next week starts Monday 9th.
        val h = Habit(id = "h", name = "Free game", repeatRule = "RRULE:FREQ=WEEKLY;TT_TIMES=1", reminders = listOf("09:00"), remindFrom = 5)
        val r = plan(NotificationPrefs(summary = false), habits = listOf(h))
        assertEquals(listOf(ms("2026-11-13T09:00"), ms("2026-11-14T09:00")), r.map { it.fireAt })
        // Done on Friday: nothing more that week.
        val done = HabitCheckin(id = "c", habitId = "h", stamp = 20261113, status = 2, value = 1.0)
        assertEquals(emptyList(), plan(NotificationPrefs(summary = false), habits = listOf(h), checkins = listOf(done)).map { it.fireAt })
    }

    @Test
    fun monthlyHabitsRemindFromTheirStartDayOfTheMonth() {
        // Twice a month from the 10th; done once on the 3rd, so it still needs one more.
        val h = Habit(id = "h", name = "Backup", repeatRule = "RRULE:FREQ=MONTHLY;TT_TIMES=2", reminders = listOf("20:00"), remindFrom = 10)
        val done = HabitCheckin(id = "c", habitId = "h", stamp = 20261103, status = 2, value = 1.0)
        val r = plan(NotificationPrefs(summary = false), habits = listOf(h), checkins = listOf(done))
        assertEquals((10..14).map { ms("2026-11-${it}T20:00") }, r.map { it.fireAt })
    }

    @Test
    fun countdownsUseTheirOwnReminders() {
        val c = Countdown(id = "c", name = "Exam", date = 20261125, reminders = listOf("14@08:00", "7@09:30", "0@07:00"))
        val r = plan(NotificationPrefs(summary = false), countdowns = listOf(c))
        // 14 days before (11th) at 08:00, 7 days before (18th) at 09:30, on the day at 07:00.
        assertEquals(listOf(ms("2026-11-11T08:00"), ms("2026-11-18T09:30"), ms("2026-11-25T07:00")), r.map { it.fireAt })
        // An empty list means no reminders.
        assertEquals(emptyList(), plan(NotificationPrefs(summary = false), countdowns = listOf(c.copy(reminders = emptyList()))))
    }

    @Test
    fun habitsRemindOnDueDaysNotDoneYet() {
        // Weekly on Monday and Wednesday at 07:00; done already on Monday 9th.
        val h = Habit(id = "h", name = "Run", repeatRule = "RRULE:FREQ=WEEKLY;BYDAY=MO,WE", reminders = listOf("07:00"), encouragement = "Keep going")
        val done = HabitCheckin(id = "c", habitId = "h", stamp = 20261109, status = 2, value = 1.0)
        val r = plan(NotificationPrefs(summary = false), habits = listOf(h), checkins = listOf(done))
        assertEquals(listOf(ms("2026-11-11T07:00")), r.map { it.fireAt })
        val n = r.single()
        assertEquals(PlannedReminder.KIND_HABIT, n.kind)
        assertEquals("h", n.habitId)
        assertEquals(20261111, n.stamp)
        assertEquals("Run", n.title)
        assertEquals("Keep going", n.body)
    }

    @Test
    fun habitTimesEarlierTodayAndArchivedHabitsAreSkipped() {
        val daily = Habit(id = "d", name = "Water", reminders = listOf("09:00", "21:00"))
        val archived = daily.copy(id = "x", archivedAt = 1)
        val r = plan(NotificationPrefs(summary = false), habits = listOf(daily, archived)).filter { it.fireAt < ms("2026-11-09T00:00") }
        // 09:00 today has passed; 21:00 is still ahead.
        assertEquals(listOf(ms("2026-11-08T21:00")), r.map { it.fireAt })
    }

    @Test
    fun countdownsRemindTheDayBeforeAndOnTheDay() {
        val c = Countdown(id = "c", name = "Mom's birthday", date = 19601110, type = "BIRTHDAY", repeat = "yearly")
        val r = plan(NotificationPrefs(summary = false, countdownMinutes = 8 * 60), countdowns = listOf(c))
        assertEquals(listOf(ms("2026-11-09T08:00"), ms("2026-11-10T08:00")), r.map { it.fireAt })
        assertTrue(r.all { it.kind == PlannedReminder.KIND_COUNTDOWN && it.countdownId == "c" })
        assertEquals("Tomorrow", r[0].body)
        assertEquals("Today · 66 years", r[1].body)
    }

    @Test
    fun countdownOnlyOnTheDayWhenNoLeadTime() {
        val c = Countdown(id = "c", name = "Trip", date = 20261110)
        val r = plan(NotificationPrefs(summary = false, countdownDaysBefore = 0), countdowns = listOf(c))
        assertEquals(listOf(ms("2026-11-10T09:00")), r.map { it.fireAt })
    }

    @Test
    fun summaryListsTheDaysTasksAndOverdue() {
        val tasks = listOf(
            Task(id = "1", title = "Standup", dueDate = ms("2026-11-09T09:00")),
            Task(id = "2", title = "Review", dueDate = ms("2026-11-09T14:00")),
            Task(id = "3", title = "Old", dueDate = ms("2026-11-07T09:00")),
            Task(id = "4", title = "Done", dueDate = ms("2026-11-09T10:00"), status = 2),
        )
        val r = plan(NotificationPrefs(tasks = false), tasks = tasks).filter { it.kind == PlannedReminder.KIND_SUMMARY }
        // Today's 08:00 has passed; tomorrow's summary counts the overdue task too.
        val monday = r.first()
        assertEquals(ms("2026-11-09T08:00"), monday.fireAt)
        assertEquals("2 tasks today, 1 overdue", monday.title)
        assertEquals("Standup, Review", monday.body)
    }

    @Test
    fun summarySkipsEmptyDaysAndRespectsSwitches() {
        val off = NotificationPrefs(tasks = false, habits = false, countdowns = false, summary = false)
        val t = Task(id = "1", title = "A", dueDate = ms("2026-11-09T09:00"), reminders = listOf("TRIGGER:PT0S"))
        assertEquals(emptyList(), plan(off, tasks = listOf(t), habits = listOf(Habit(id = "h", reminders = listOf("20:00")))))
        // Only one day has tasks, so only one summary.
        assertEquals(1, plan(NotificationPrefs(tasks = false), tasks = listOf(t)).size)
    }

    @Test
    fun longSummaryBodyIsShortened() {
        val tasks = (1..5).map { Task(id = "$it", title = "T$it", dueDate = ms("2026-11-09T09:0$it")) }
        val s = plan(NotificationPrefs(tasks = false), tasks = tasks).single()
        assertEquals("5 tasks today", s.title)
        assertEquals("T1, T2, T3 and 2 more", s.body)
    }

    @Test
    fun soonestFirstUpToTheLimit() {
        val h = Habit(id = "h", name = "Water", reminders = listOf("12:00"))
        val r = plan(NotificationPrefs(summary = false), habits = listOf(h), limit = 3)
        assertEquals(listOf(ms("2026-11-08T12:00"), ms("2026-11-09T12:00"), ms("2026-11-10T12:00")), r.map { it.fireAt })
        assertEquals(3, r.map { it.id }.toSet().size)
    }

    @Test
    fun snoozeTimes() {
        val at = now.toEpochMilliseconds()
        assertEquals(at + 5 * 60_000, Snooze.until(Snooze.FIVE_MINUTES, now, zone, NotificationPrefs()))
        assertEquals(at + 3_600_000, Snooze.until(Snooze.ONE_HOUR, now, zone, NotificationPrefs()))
        assertEquals(ms("2026-11-09T09:00"), Snooze.until(Snooze.TOMORROW, now, zone, NotificationPrefs()))
    }

    @Test
    fun prefsDecodeFromOlderJson() {
        assertEquals(NotificationPrefs(summary = false), NotificationPrefs.parse("""{"summary":false}"""))
        assertEquals(NotificationPrefs(), NotificationPrefs.parse("not json"))
    }
}
