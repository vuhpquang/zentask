package com.vuhpquang.zentask.lists

import com.vuhpquang.zentask.model.Filter
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.TaskIndex
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiltersTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val now = LocalDateTime.parse("2026-11-08T10:00").toInstant(zone)
    private val projects = listOf(Project(id = "work", name = "Work"), Project(id = "home", name = "Home"))
    private val tasks = listOf(
        Task(id = "a", title = "Ship report", projectId = "work", priority = 5, tags = listOf("q4"), dueDate = ms("2026-11-08T09:00")),
        Task(id = "b", title = "Buy milk", projectId = "home", priority = 0, dueDate = ms("2026-11-09T09:00")),
        Task(id = "c", title = "Report bug", projectId = "work", priority = 3),
        Task(id = "d", title = "Old", projectId = "work", priority = 5, dueDate = ms("2026-11-01T09:00")),
        Task(id = "e", title = "Done report", projectId = "work", status = 2, priority = 5),
    )

    private fun ids(rule: String): Set<String> {
        val index = TaskIndex(tasks, projects, emptyList(), emptyList(), emptyList(), listOf(Filter(id = "f", name = "F", rule = rule)))
        return index.rows(TaskFilter.Custom("f"), now, zone).map { it.task.id }.toSet()
    }

    @Test
    fun fieldsCombineWithAndValuesWithOr() {
        assertEquals(setOf("a", "c", "d"), ids("""{"lists":["work"]}"""))
        assertEquals(setOf("a", "d"), ids("""{"lists":["work"],"priorities":[5]}"""))
        assertEquals(setOf("a", "b", "c", "d"), ids("""{"lists":["work","home"]}"""))
        assertEquals(setOf("a"), ids("""{"tags":["Q4"]}"""))
        assertEquals(setOf("a", "c"), ids("""{"keyword":"report"}"""))
    }

    @Test
    fun dueRanges() {
        assertEquals(setOf("a", "d"), ids("""{"due":"today"}"""), "today includes overdue")
        assertEquals(setOf("b"), ids("""{"due":"tomorrow"}"""))
        assertEquals(setOf("d"), ids("""{"due":"overdue"}"""))
        assertEquals(setOf("c"), ids("""{"due":"nodate"}"""))
        assertEquals(setOf("a", "b", "d"), ids("""{"due":"next7"}"""))
    }

    @Test
    fun unknownKeysIgnoredBadRulesMatchNothing() {
        assertEquals(setOf("a", "c", "d"), ids("""{"lists":["work"],"future":true}"""))
        assertTrue(ids("not json").isEmpty())
        assertTrue(ids("""{"priorities":"high"}""").isEmpty())
        assertNull(FilterRule.parse("[]"))
        assertEquals(FilterRule(), FilterRule.parse("{}"))
    }

    @Test
    fun ruleRoundTrip() {
        val r = FilterRule(lists = listOf("work"), tags = listOf("q4"), priorities = listOf(5, 3), due = "today", keyword = "x")
        assertEquals(r, FilterRule.parse(r.encode()))
    }

    @Test
    fun customCountsAndSections() {
        val index = TaskIndex(tasks, projects, emptyList(), emptyList(), emptyList(), listOf(Filter(id = "f", name = "Hot", rule = """{"priorities":[5]}""")))
        assertEquals(2, index.counts(now, zone)[TaskFilter.Custom("f")])
        assertEquals(listOf("Overdue", "Today"), index.sections(TaskFilter.Custom("f"), now, zone).map { it.title }, "open tasks only, like the built-in smart lists")
    }

    @Test
    fun viewOptionsSortAndGroup() {
        val index = TaskIndex(tasks, projects, emptyList(), emptyList(), emptyList())
        val byPriority = index.sections(TaskFilter.InProject("work"), now, zone, ViewOptions(sort = "title", group = "priority"))
        assertEquals(listOf("High", "Medium", "Completed"), byPriority.map { it.title })
        assertEquals(listOf("d", "a"), byPriority.first().rows.map { it.task.id }, "sorted by title: Old, Ship report")
        val byList = index.sections(TaskFilter.Smart(com.vuhpquang.zentask.tasks.SmartList.ALL), now, zone, ViewOptions(sort = "priority", group = "list"))
        assertEquals(listOf("Home", "Work"), byList.map { it.title })
        assertEquals(listOf("d", "a", "c"), byList.last().rows.map { it.task.id }, "priority, then date")
        val flat = index.sections(TaskFilter.Smart(com.vuhpquang.zentask.tasks.SmartList.ALL), now, zone, ViewOptions(sort = "title", group = "none"))
        assertEquals(listOf(""), flat.map { it.title })
    }

    @Test
    fun viewOptionsPreferenceRoundTrip() {
        val o = ViewOptions(sort = "priority", group = "tag", showCompleted = true)
        assertEquals(o, ViewOptions.parse(o.encode()))
        assertEquals(ViewOptions(), ViewOptions.parse("garbage"))
        assertEquals("view.project:work", ViewOptions.key("project:work"))
    }

    @Test
    fun projectSortKeepsSectionsAndDateSortSorts() {
        val cols = listOf(com.vuhpquang.zentask.model.Column(id = "c1", projectId = "work", name = "Todo"))
        val withCol = tasks.map { if (it.id == "c") it.copy(columnId = "c1") else it }
        val index = TaskIndex(withCol, projects, emptyList(), cols, emptyList())
        val byPrio = index.sections(TaskFilter.InProject("work"), now, zone, ViewOptions(sort = "priority"))
        assertEquals(listOf("Todo", "No section", "Completed"), byPrio.map { it.title }, "a list keeps its sections")
        assertEquals(listOf("d", "a"), byPrio[1].rows.map { it.task.id })
        val byDate = index.sections(TaskFilter.InProject("work"), now, zone, ViewOptions(sort = "date", group = "none"))
        assertEquals(listOf("d", "a", "c"), byDate.first().rows.map { it.task.id }, "dated first, oldest first")
    }
}
