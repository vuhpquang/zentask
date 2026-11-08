package com.vuhpquang.zentask.calendar

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarQueriesTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val accounts = listOf(CalendarAccount(id = "acc", email = "me@x"))
    private val sources = listOf(CalendarSource(id = "s1", accountId = "acc", name = "Me", color = "#2E9E88"), CalendarSource(id = "s2", accountId = "acc", name = "Work", color = "#3B82F6"))
    private fun ev(id: String, from: String, to: String, source: String = "s1", allDay: Boolean = false) =
        CalendarEvent(id = id, sourceId = source, title = id, start = ms(from), end = ms(to), isAllDay = allDay)

    private fun days(events: List<CalendarEvent>, tasks: List<Task> = emptyList(), hidden: Set<String> = emptySet(), count: Int = 7) =
        CalendarQueries.days(events, sources, accounts, tasks, LocalDate(2026, 11, 9), count, zone, hidden)

    @Test
    fun eventsLandOnTheirDaysWithColour() {
        val d = days(listOf(ev("standup", "2026-11-09T09:00", "2026-11-09T09:15"), ev("review", "2026-11-10T14:00", "2026-11-10T15:00", "s2")))
        assertEquals(7, d.size)
        assertEquals(listOf("standup"), d[0].timed.map { it.entry.title })
        assertEquals(540, d[0].timed[0].startMinute)
        assertEquals(555, d[0].timed[0].endMinute)
        assertEquals("#3B82F6", d[1].timed.single().entry.color)
    }

    @Test
    fun multiDayAllDayAndOvernightEvents() {
        val d = days(listOf(ev("trip", "2026-11-10T00:00", "2026-11-13T00:00", allDay = true), ev("night", "2026-11-09T22:00", "2026-11-10T02:00")))
        assertEquals(listOf(emptyList(), listOf("trip"), listOf("trip"), listOf("trip"), emptyList()), d.take(5).map { day -> day.allDay.map { it.title } })
        assertEquals(1320 to 1440, d[0].timed.single().let { it.startMinute to it.endMinute })
        assertEquals(0 to 120, d[1].timed.single().let { it.startMinute to it.endMinute })
    }

    @Test
    fun hiddenCalendarsAndDeletedRecordsAreLeftOut() {
        val d = days(listOf(ev("a", "2026-11-09T09:00", "2026-11-09T10:00"), ev("b", "2026-11-09T09:00", "2026-11-09T10:00", "s2"), ev("c", "2026-11-09T11:00", "2026-11-09T12:00").copy(deleted = true)), hidden = setOf("s2"))
        assertEquals(listOf("a"), d[0].timed.map { it.entry.title })
    }

    @Test
    fun tasksShowWithDefaultDurationAndAllDayRow() {
        val tasks = listOf(
            Task(id = "t1", title = "Call", dueDate = ms("2026-11-09T10:00")),
            Task(id = "t2", title = "Pay rent", dueDate = ms("2026-11-09T00:00"), isAllDay = true, timeZone = zone.id),
            Task(id = "t3", title = "Range", startDate = ms("2026-11-09T13:00"), dueDate = ms("2026-11-09T15:00")),
            Task(id = "t4", title = "Done", dueDate = ms("2026-11-09T16:00"), status = 2),
            Task(id = "t5", title = "Trashed", dueDate = ms("2026-11-09T17:00"), trashedAt = 1),
        )
        val day = days(emptyList(), tasks)[0]
        assertEquals(listOf("Pay rent"), day.allDay.map { it.title })
        val timed = day.timed.associate { it.entry.title to (it.startMinute to it.endMinute) }
        assertEquals(600 to 630, timed["Call"])
        assertEquals(780 to 900, timed["Range"])
        assertTrue(day.timed.single { it.entry.title == "Done" }.entry.done)
        assertTrue("Trashed" !in timed)
        assertEquals("task", day.timed.first().entry.kind)
    }

    @Test
    fun overlappingItemsShareColumns() {
        val d = days(listOf(
            ev("a", "2026-11-09T09:00", "2026-11-09T10:00"), ev("b", "2026-11-09T09:30", "2026-11-09T10:30"),
            ev("c", "2026-11-09T10:00", "2026-11-09T11:00"), ev("d", "2026-11-09T12:00", "2026-11-09T13:00"),
        ))
        val cols = d[0].timed.associate { it.entry.title to (it.column to it.columns) }
        assertEquals(0 to 2, cols["a"])
        assertEquals(1 to 2, cols["b"])
        assertEquals(0 to 2, cols["c"], "c starts when a ends: reuses column 0")
        assertEquals(0 to 1, cols["d"])
    }

    @Test
    fun allDayEventFromAnotherZoneStaysOnItsDate() {
        val la = TimeZone.of("America/Los_Angeles")
        val start = LocalDateTime.parse("2026-11-10T00:00").toInstant(la).toEpochMilliseconds()
        val end = LocalDateTime.parse("2026-11-11T00:00").toInstant(la).toEpochMilliseconds()
        val e = CalendarEvent(id = "la", sourceId = "s1", title = "LA holiday", start = start, end = end, isAllDay = true, timeZone = "America/Los_Angeles")
        val d = days(listOf(e))
        assertEquals(listOf(emptyList(), listOf("LA holiday"), emptyList()), d.take(3).map { day -> day.allDay.map { it.title } })
    }

    @Test
    fun countdownsShowOnTheirDay() {
        val cds = listOf(
            com.vuhpquang.zentask.model.Countdown(id = "b", name = "Mom's birthday", date = 19601110, repeat = "yearly", showInCalendar = true),
            com.vuhpquang.zentask.model.Countdown(id = "h", name = "Hidden", date = 20261111, showInCalendar = false),
        )
        val d = CalendarQueries.days(emptyList(), sources, accounts, emptyList(), LocalDate(2026, 11, 9), 7, zone, emptySet(), cds)
        assertEquals(listOf("Mom's birthday"), d[1].allDay.map { it.title })
        assertEquals("countdown", d[1].allDay.single().kind)
        assertTrue(d.flatMap { it.allDay }.none { it.title == "Hidden" })
    }

    @Test
    fun viewOptionsFilterListsAndShowHabits() {
        val day = LocalDate(2026, 11, 9)
        val at = { h: Int -> kotlinx.datetime.LocalDateTime(2026, 11, 9, h, 0).toInstant(zone).toEpochMilliseconds() }
        val tasks = listOf(
            com.vuhpquang.zentask.model.Task(id = "w", title = "Work thing", projectId = "work", dueDate = at(9), tags = listOf("deep")),
            com.vuhpquang.zentask.model.Task(id = "h", title = "Home thing", projectId = "home", dueDate = at(10)),
            com.vuhpquang.zentask.model.Task(id = "d", title = "Done thing", projectId = "home", dueDate = at(11), status = 2),
        )
        val habits = listOf(
            com.vuhpquang.zentask.model.Habit(id = "gym", name = "Gym", reminders = listOf("19:00"), color = "#EC4899"),
            com.vuhpquang.zentask.model.Habit(id = "read", name = "Read"),
            com.vuhpquang.zentask.model.Habit(id = "old", name = "Old", archivedAt = 1),
        )
        val checkins = listOf(com.vuhpquang.zentask.model.HabitCheckin(id = "c", habitId = "read", stamp = 20261109, status = 2))
        val projects = listOf(com.vuhpquang.zentask.model.Project(id = "work", name = "Work", color = "#3B82F6"))
        fun titles(o: CalendarViewOptions) = CalendarQueries.days(
            emptyList(), sources, accounts, tasks, day, 1, zone, emptySet(), emptyList(), o, habits, checkins, projects,
        ).single().let { d -> (d.allDay + d.timed.map { it.entry }).map { it.title }.toSet() }

        assertEquals(setOf("Work thing", "Home thing", "Done thing", "Gym", "Read"), titles(CalendarViewOptions()))
        assertEquals(setOf("Work thing", "Home thing", "Gym"), titles(CalendarViewOptions(showCompleted = false)), "done tasks and checked-in habits hidden")
        assertEquals(setOf("Work thing", "Home thing", "Done thing"), titles(CalendarViewOptions(showHabits = false)))
        assertEquals(setOf("Work thing", "Gym", "Read"), titles(CalendarViewOptions(lists = listOf("work"))))
        assertEquals(setOf("Work thing", "Gym", "Read"), titles(CalendarViewOptions(tags = listOf("deep"))))

        val d = CalendarQueries.days(emptyList(), sources, accounts, tasks, day, 1, zone, emptySet(), emptyList(), CalendarViewOptions(colorBy = "list"), habits, checkins, projects).single()
        val gym = d.timed.single { it.entry.kind == "habit" }
        assertEquals(19 * 60, gym.startMinute)
        assertEquals("gym", gym.entry.id.substringBefore("@"))
        assertTrue(d.allDay.single { it.title == "Read" }.done, "checked-in habits show as done")
        assertEquals("#3B82F6", d.timed.single { it.entry.id == "w" }.entry.color, "colour by list")
        assertEquals(CalendarViewOptions(), CalendarViewOptions.parse("not json"))
    }

    @Test
    fun habitsStayOffTheCalendarBeforeTheyExistAndOnceTheWeekIsDone() {
        val created = LocalDate(2026, 11, 10).atStartOfDayIn(zone).toEpochMilliseconds()
        val habits = listOf(
            com.vuhpquang.zentask.model.Habit(id = "new", name = "New", createdAt = created),
            com.vuhpquang.zentask.model.Habit(id = "twice", name = "Twice", repeatRule = "RRULE:FREQ=WEEKLY;TT_TIMES=1"),
        )
        val checkins = listOf(com.vuhpquang.zentask.model.HabitCheckin(id = "c", habitId = "twice", stamp = 20261109, status = 2))
        val d = CalendarQueries.days(emptyList(), sources, accounts, emptyList(), LocalDate(2026, 11, 9), 3, zone, emptySet(), emptyList(), CalendarViewOptions(), habits, checkins, emptyList())
        assertEquals(listOf("Twice"), d[0].allDay.map { it.title }, "Mon: New doesn't exist yet; Twice done")
        assertEquals(listOf("New"), d[1].allDay.map { it.title }, "Tue: Twice's week is done")
        assertEquals(listOf("New"), d[2].allDay.map { it.title })
    }

    @Test
    fun hidingCompletedAlsoHidesDoneHabits() {
        val habits = listOf(com.vuhpquang.zentask.model.Habit(id = "a@b", name = "Odd id"))
        val checkins = listOf(com.vuhpquang.zentask.model.HabitCheckin(id = "c", habitId = "a@b", stamp = 20261109, status = 2))
        fun day(o: CalendarViewOptions) = CalendarQueries.days(emptyList(), sources, accounts, emptyList(), LocalDate(2026, 11, 9), 1, zone, emptySet(), emptyList(), o, habits, checkins, emptyList()).single()
        assertEquals("a@b@20261109", day(CalendarViewOptions()).allDay.single().id)
        assertTrue(day(CalendarViewOptions(showCompleted = false)).allDay.isEmpty())
    }
}
