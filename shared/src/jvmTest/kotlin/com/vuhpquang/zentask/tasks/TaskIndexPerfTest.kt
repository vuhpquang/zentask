package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.measureTime

/** Focus 4 of the P3 plan: list switching must stay instant with thousands of tasks. */
class TaskIndexPerfTest {
    @Test
    fun fiveThousandTasksSectionInWellUnderAFrameBudget() {
        val zone = TimeZone.of("Asia/Ho_Chi_Minh")
        val now = Clock.System.now()
        val base = now.toEpochMilliseconds()
        val projects = (0 until 20).map { Project(id = "p$it", name = "List $it") }
        val tasks = (0 until 5_000).map { i ->
            Task(
                id = "t$i", title = "Task $i", projectId = "p${i % 20}", priority = listOf(0, 1, 3, 5)[i % 4],
                dueDate = if (i % 3 == 0) null else base + (i % 30 - 10) * 86_400_000L, isAllDay = i % 2 == 0,
                timeZone = if (i % 2 == 0) "Asia/Saigon" else null, tags = listOf("t${i % 10}"),
                status = if (i % 7 == 0) 2 else 0, parentId = if (i % 11 == 0 && i > 0) "t${i - 1}" else null,
            )
        }
        val index = TaskIndex(tasks, projects, emptyList(), emptyList(), emptyList())
        val filters = SmartList.entries.map { TaskFilter.Smart(it) } + TaskFilter.InProject("p3") + TaskFilter.WithTag("t4")
        repeat(3) { filters.forEach { index.sections(it, now, zone) } } // warm up the JIT
        val perSwitch = measureTime { filters.forEach { index.sections(it, now, zone) } } / filters.size
        println("sections per list switch, 5000 tasks: $perSwitch")
        assertTrue(perSwitch.inWholeMilliseconds < 50, "a list switch took $perSwitch")
    }
}
