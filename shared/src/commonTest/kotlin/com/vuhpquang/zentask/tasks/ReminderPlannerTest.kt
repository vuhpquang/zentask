package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderPlannerTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val now = LocalDateTime.parse("2026-11-08T10:00").toInstant(zone)

    private fun plan(vararg tasks: Task) = ReminderPlanner.upcoming(tasks.toList(), now, zone)

    @Test
    fun relativeTriggers() {
        val t = Task(id = "a", title = "Standup", dueDate = ms("2026-11-08T14:00"), reminders = listOf("TRIGGER:-PT15M", "TRIGGER:PT0S", "TRIGGER:-P1D"))
        val r = plan(t)
        assertEquals(listOf(ms("2026-11-08T13:45"), ms("2026-11-08T14:00")), r.map { it.fireAt })
        assertEquals("Standup", r.first().title)
        assertEquals(2, r.map { it.id }.toSet().size)
    }

    @Test
    fun allDayOnTimeMeansNineAndPositiveOffsetsCountFromMidnight() {
        val t = Task(id = "a", dueDate = ms("2026-11-09T00:00"), isAllDay = true, timeZone = zone.id, reminders = listOf("TRIGGER:PT0S", "TRIGGER:P0DT17H50M0S", "TRIGGER:-P0DT15H0M0S"))
        // -15h from midnight = 08 Nov 09:00, already past at 10:00.
        assertEquals(listOf(ms("2026-11-09T09:00"), ms("2026-11-09T17:50")), plan(t).map { it.fireAt })
    }

    @Test
    fun skipsDoneTrashedAndOutOfWindow() {
        val base = Task(id = "x", dueDate = ms("2026-11-08T20:00"), reminders = listOf("TRIGGER:PT0S"))
        val r = plan(
            base, base.copy(id = "done", status = 2), base.copy(id = "trash", trashedAt = 1), base.copy(id = "del", deleted = true),
            base.copy(id = "far", dueDate = ms("2026-11-30T09:00")), base.copy(id = "none", reminders = emptyList()), base.copy(id = "nodate", dueDate = null),
        )
        assertEquals(listOf("x"), r.map { it.taskId })
    }

    @Test
    fun parsesDurations() {
        assertEquals(-15 * 60_000L, ReminderPlanner.offsetMillis("TRIGGER:-PT15M"))
        assertEquals(0L, ReminderPlanner.offsetMillis("PT0S"))
        assertEquals(-86_400_000L, ReminderPlanner.offsetMillis("-P1D"))
        assertEquals((17 * 60 + 50) * 60_000L, ReminderPlanner.offsetMillis("P0DT17H50M0S"))
        assertEquals(-7 * 86_400_000L, ReminderPlanner.offsetMillis("-P1W"))
        assertEquals(null, ReminderPlanner.offsetMillis("soon"))
    }

    @Test
    fun soonestFirstAndCapped() {
        val tasks = (0 until 100).map { Task(id = "t$it", dueDate = ms("2026-11-08T11:00") + it * 60_000L, reminders = listOf("TRIGGER:PT0S")) }
        val r = ReminderPlanner.upcoming(tasks, now, zone, limit = 60)
        assertEquals(60, r.size)
        assertTrue(r.zipWithNext().all { (a, b) -> a.fireAt <= b.fireAt })
    }
}
