package com.vuhpquang.zentask.calendar

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
}
