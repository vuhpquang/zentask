package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val now = LocalDateTime.parse("2026-11-14T10:00").toInstant(zone) // Saturday
    private fun done(id: String, at: String, project: String = "inbox") =
        Task(id = id, title = id, projectId = project, status = TaskStatus.COMPLETED, completedAt = ms(at))

    @Test
    fun weeksStreaksAndLists() {
        val tasks = listOf(
            done("a", "2026-11-14T08:00", "work"), done("b", "2026-11-13T08:00", "work"), done("c", "2026-11-12T08:00"),
            done("d", "2026-11-10T08:00"), // Tuesday: a gap on Wednesday 11th breaks the streak
            done("e", "2026-11-04T08:00", "work"), // last week
            Task(id = "open", title = "open"),
        )
        val s = ProductivityStats.of(tasks, listOf(Project(id = "work", name = "Work")), emptyList(), now, zone)
        assertEquals(4, s.thisWeek)
        assertEquals(1, s.lastWeek)
        assertEquals(3, s.streak)
        assertEquals(3, s.bestStreak)
        assertEquals(5, s.total)
        assertEquals(listOf("Work" to 3, "Inbox" to 2), s.byList.map { it.name to it.done })
        assertEquals(30, s.days.size)
        assertEquals(20261114, s.days.last().stamp)
        assertEquals(1, s.days.last().done)
    }

    @Test
    fun streakSurvivesUntilTodayHasATask() {
        val s = ProductivityStats.of(listOf(done("y", "2026-11-13T22:00")), emptyList(), emptyList(), now, zone)
        assertEquals(1, s.streak)
    }
}
