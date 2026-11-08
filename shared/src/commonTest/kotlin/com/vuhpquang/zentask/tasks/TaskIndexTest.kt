package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskIndexTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String, z: TimeZone = zone) = LocalDateTime.parse(s).toInstant(z).toEpochMilliseconds()
    private val now = LocalDateTime.parse("2026-11-08T10:00").toInstant(zone) // Sunday

    private val projects = listOf(
        Project(id = "inbox", name = "Inbox", isInbox = true),
        Project(id = "work", name = "🏢Works", sortOrder = 1),
        Project(id = "old", name = "Archived", closed = true),
    )

    private fun t(id: String, due: String? = null, allDay: Boolean = false, project: String = "inbox", status: Int = 0, vararg tags: String, parent: String? = null, tz: String? = null, sort: Long = 0, priority: Int = 0) =
        Task(id = id, title = id, projectId = project, status = status, tags = tags.toList(), parentId = parent, sortOrder = sort, priority = priority,
            dueDate = due?.let { ms(it, tz?.let(TimeZone::of) ?: zone) }, isAllDay = allDay, timeZone = tz)

    private fun index(vararg tasks: Task, items: List<ChecklistItem> = emptyList(), columns: List<Column> = emptyList(), tags: List<Tag> = emptyList()) =
        TaskIndex(tasks.toList(), projects, items, columns, tags)

    private fun ids(i: TaskIndex, f: TaskFilter) = i.rows(f, now, zone).map { it.task.id }

    @Test
    fun todayIncludesOverdueAndAllDay() {
        val i = index(
            t("overdue", "2026-11-07T09:00"), t("today-timed", "2026-11-08T23:30"), t("today-allday", "2026-11-08T00:00", allDay = true),
            t("tomorrow", "2026-11-09T00:30"), t("nodate"), t("done-today", "2026-11-08T08:00", status = TaskStatus.COMPLETED),
        )
        assertEquals(setOf("overdue", "today-timed", "today-allday"), ids(i, TaskFilter.Smart(SmartList.TODAY)).toSet())
        assertEquals(listOf("tomorrow"), ids(i, TaskFilter.Smart(SmartList.TOMORROW)))
        val sections = i.sections(TaskFilter.Smart(SmartList.TODAY), now, zone)
        assertEquals(listOf("Overdue", "Today"), sections.map { it.title })
        assertTrue(i.rows(TaskFilter.Smart(SmartList.TODAY), now, zone).single { it.task.id == "overdue" }.overdue)
    }

    @Test
    fun allDayDateUsesTheTasksOwnZone() {
        // Imported all-day task stored as local midnight in Asia/Saigon (same offset): still "today".
        val i = index(t("saigon", "2026-11-08T00:00", allDay = true, tz = "Asia/Saigon"))
        assertEquals(listOf("saigon"), ids(i, TaskFilter.Smart(SmartList.TODAY)))
    }

    @Test
    fun nextSevenDaysGroupsByDay() {
        val i = index(t("mon", "2026-11-09T09:00"), t("sat", "2026-11-14T09:00"), t("far", "2026-11-20T09:00"))
        assertEquals(listOf("mon", "sat"), ids(i, TaskFilter.Smart(SmartList.NEXT_7_DAYS)))
        assertEquals(listOf("Tomorrow", "Saturday"), i.sections(TaskFilter.Smart(SmartList.NEXT_7_DAYS), now, zone).map { it.title })
    }

    @Test
    fun statusListsAndTrash() {
        val i = index(
            t("open"), t("done", status = 2), t("legacy-done", status = 1), t("wont", status = -1),
            t("trashed").copy(trashedAt = 1), t("deleted").copy(deleted = true), t("archived-list", project = "old"),
        )
        assertEquals(listOf("open"), ids(i, TaskFilter.Smart(SmartList.ALL)))
        assertEquals(setOf("done", "legacy-done"), ids(i, TaskFilter.Smart(SmartList.COMPLETED)).toSet())
        assertEquals(listOf("wont"), ids(i, TaskFilter.Smart(SmartList.WONT_DO)))
        assertEquals(listOf("trashed"), ids(i, TaskFilter.Smart(SmartList.TRASH)))
        assertEquals(listOf("open"), ids(i, TaskFilter.Smart(SmartList.INBOX)))
    }

    @Test
    fun projectListHasColumnsAndCompletedLast() {
        val cols = listOf(Column(id = "c2", projectId = "work", name = "Doing", sortOrder = 2), Column(id = "c1", projectId = "work", name = "Todo", sortOrder = 1))
        val i = index(
            t("a", project = "work", sort = 2).copy(columnId = "c2"), t("b", project = "work", sort = 1).copy(columnId = "c1"),
            t("c", project = "work"), t("d", project = "work", status = 2), columns = cols,
        )
        val sections = i.sections(TaskFilter.InProject("work"), now, zone)
        assertEquals(listOf("Todo", "Doing", "No section", "Completed"), sections.map { it.title })
        assertTrue(sections.last().isCompleted)
    }

    @Test
    fun subtasksNestUnderParent() {
        val i = index(t("parent", project = "work"), t("child", project = "work", parent = "parent"), t("orphan", project = "work", parent = "gone"))
        val rows = i.rows(TaskFilter.InProject("work"), now, zone)
        assertEquals(listOf("parent", "orphan"), rows.map { it.task.id })
        assertEquals(listOf("child"), rows.first().subtasks.map { it.id })
    }

    @Test
    fun tagFilterIsCaseInsensitiveAndIncludesChildTags() {
        val tags = listOf(Tag(id = "t1", name = "work"), Tag(id = "t2", name = "meetings", parent = "work"))
        val i = index(t("a", tags = arrayOf("work")), t("b", tags = arrayOf("meetings")), t("c", tags = arrayOf("home")), tags = tags)
        assertEquals(setOf("a", "b"), ids(i, TaskFilter.WithTag("Work")).toSet())
    }

    @Test
    fun checklistProgressAndProjectName() {
        val items = listOf(
            ChecklistItem(id = "i1", taskId = "a", status = 2), ChecklistItem(id = "i2", taskId = "a"),
            ChecklistItem(id = "i3", taskId = "a", deleted = true),
        )
        val row = index(t("a", project = "work"), items = items).rows(TaskFilter.Smart(SmartList.ALL), now, zone).single()
        assertEquals(1, row.checklistDone)
        assertEquals(2, row.checklistTotal)
        assertEquals("🏢Works", row.projectName)
    }

    @Test
    fun countsForSidebar() {
        val i = index(t("a", "2026-11-08T09:00"), t("b", project = "work", tags = arrayOf("x")), t("c", status = 2))
        val counts = i.counts(now, zone)
        assertEquals(1, counts[TaskFilter.Smart(SmartList.TODAY)])
        assertEquals(1, counts[TaskFilter.Smart(SmartList.INBOX)])
        assertEquals(2, counts[TaskFilter.Smart(SmartList.ALL)])
        assertEquals(1, counts[TaskFilter.InProject("work")])
        assertEquals(1, counts[TaskFilter.WithTag("x")])
    }

    @Test
    fun sortsByDateThenPriorityInDateLists() {
        val i = index(t("late", "2026-11-08T20:00"), t("early-low", "2026-11-08T08:00", priority = 1), t("early-high", "2026-11-08T08:00", priority = 5))
        assertEquals(listOf("early-high", "early-low", "late"), ids(i, TaskFilter.Smart(SmartList.TODAY)))
    }

    @Test
    fun sectionsHaveStableKeysColoursAndOrder() {
        val coloured = listOf(Project(id = "inbox", name = "Inbox", isInbox = true), Project(id = "work", name = "Works", color = "#EC4899", sortOrder = 1))
        val i = TaskIndex(
            listOf(
                t("a", priority = 5, project = "work"), t("b", priority = 1, project = "work"), t("c", priority = 3),
                t("d", status = TaskStatus.COMPLETED, project = "work"),
            ),
            coloured, emptyList(), emptyList(), emptyList(),
        )
        val byList = i.sections(TaskFilter.InProject("work"), now, zone, com.vuhpquang.zentask.lists.ViewOptions(group = "list", sort = "priority"))
        assertEquals(listOf("list:work", "done"), byList.map { it.key })
        assertEquals("#EC4899", byList.first().color)
        assertEquals(listOf("a", "b"), byList.first().rows.map { it.task.id })
        val desc = i.sections(TaskFilter.InProject("work"), now, zone, com.vuhpquang.zentask.lists.ViewOptions(group = "list", sort = "priority", order = "desc"))
        assertEquals(listOf("b", "a"), desc.first().rows.map { it.task.id })
        val hidden = i.sections(TaskFilter.InProject("work"), now, zone, com.vuhpquang.zentask.lists.ViewOptions(hideCompleted = true))
        assertTrue(hidden.none { it.isCompleted })
        val all = i.sections(TaskFilter.Smart(SmartList.ALL), now, zone, com.vuhpquang.zentask.lists.ViewOptions(group = "priority"))
        assertEquals(listOf("prio:5", "prio:3", "prio:1"), all.map { it.key })
        val keys = i.sections(TaskFilter.Smart(SmartList.INBOX), now, zone).map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        assertTrue(keys.all { it.isNotEmpty() })
        assertEquals(com.vuhpquang.zentask.lists.ViewOptions(sort = "date"), com.vuhpquang.zentask.lists.ViewOptions.parse("""{"sort":"date"}"""))
    }
}
