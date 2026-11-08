package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.store.StoreLock
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Computed lists (spec §6: never stored). */
enum class SmartList { ALL, TODAY, TOMORROW, NEXT_7_DAYS, INBOX, COMPLETED, WONT_DO, TRASH }

/** What the task list shows. */
sealed interface TaskFilter {
    data class Smart(val list: SmartList) : TaskFilter
    data class InProject(val id: String) : TaskFilter
    data class WithTag(val name: String) : TaskFilter
}

/** One top-level task in a list, with its visible subtasks and checklist progress. */
data class TaskRow(
    val task: Task,
    val subtasks: List<Task>,
    val checklistDone: Int,
    val checklistTotal: Int,
    val projectName: String,
    val overdue: Boolean,
)

data class TaskSection(val title: String, val rows: List<TaskRow>, val isCompleted: Boolean = false)

val Task.isDone: Boolean get() = status == TaskStatus.COMPLETED || status == 1
val Task.isOpen: Boolean get() = status == TaskStatus.OPEN

/**
 * An in-memory view of the task data, built once per data change ([load]) and queried per selection,
 * so switching lists never re-reads or re-decodes the database. Pure: time and zone are parameters.
 */
class TaskIndex(
    tasks: List<Task>,
    val projects: List<Project>,
    items: List<ChecklistItem>,
    columns: List<Column>,
    val tags: List<Tag>,
) {
    private val tasks = tasks.filter { !it.deleted }
    private val projectById = projects.associateBy { it.id }
    private val itemsByTask = items.filter { !it.deleted }.groupBy { it.taskId }
    private val columnsByProject = columns.filter { !it.deleted }.groupBy { it.projectId }
    private val taskById = this.tasks.associateBy { it.id }
    private val childrenByParent = this.tasks.filter { it.parentId != null && it.trashedAt == null }.groupBy { it.parentId!! }

    /** Zones by id: `TimeZone.of` may read the tz database, so resolve each id once. */
    private val zones = HashMap<String, TimeZone?>()
    private val zoneLock = StoreLock()

    /** Every live (not deleted) task, for planners. */
    val allTasks: List<Task> get() = tasks

    fun task(id: String): Task? = taskById[id]

    fun items(taskId: String): List<ChecklistItem> = itemsByTask[taskId].orEmpty().sortedBy { it.sortOrder }

    fun subtasks(taskId: String): List<Task> = childrenByParent[taskId].orEmpty().sortedBy { it.sortOrder }

    fun rows(filter: TaskFilter, now: Instant, zone: TimeZone): List<TaskRow> {
        val today = now.toLocalDateTime(zone).date
        val matched = tasks.filter { matches(it, filter, today, zone) }
        val ids = matched.mapTo(HashSet()) { it.id }
        val children = matched.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId!! }
        val top = matched.filter { it.parentId == null || it.parentId !in ids }
        return sort(top, filter, zone).map { t ->
            val items = itemsByTask[t.id].orEmpty()
            TaskRow(
                task = t,
                subtasks = children[t.id].orEmpty().sortedBy { it.sortOrder },
                checklistDone = items.count { it.status == TaskStatus.COMPLETED || it.status == 1 },
                checklistTotal = items.size,
                projectName = projectById[t.projectId]?.name.orEmpty(),
                overdue = t.isOpen && isOverdue(t, now, today, zone),
            )
        }
    }

    fun sections(filter: TaskFilter, now: Instant, zone: TimeZone): List<TaskSection> {
        val rows = rows(filter, now, zone)
        val today = now.toLocalDateTime(zone).date
        return when (filter) {
            is TaskFilter.InProject -> projectSections(filter.id, rows)
            is TaskFilter.Smart -> when (filter.list) {
                SmartList.COMPLETED, SmartList.WONT_DO, SmartList.TRASH -> listOf(TaskSection("", rows)).filter { it.rows.isNotEmpty() }
                else -> dateSections(rows.filter { !it.task.isDone }, today, zone) + completed(rows)
            }
            is TaskFilter.WithTag -> dateSections(rows.filter { !it.task.isDone }, today, zone) + completed(rows)
        }
    }

    /** Open-task counts for the sidebar badges. */
    fun counts(now: Instant, zone: TimeZone): Map<TaskFilter, Int> {
        val today = now.toLocalDateTime(zone).date
        val open = tasks.filter { visible(it) && it.isOpen }
        val out = HashMap<TaskFilter, Int>()
        for (list in listOf(SmartList.ALL, SmartList.TODAY, SmartList.TOMORROW, SmartList.NEXT_7_DAYS, SmartList.INBOX)) {
            val f = TaskFilter.Smart(list)
            out[f] = open.count { matches(it, f, today, zone) }
        }
        open.groupingBy { it.projectId }.eachCount().forEach { (id, n) -> out[TaskFilter.InProject(id)] = n }
        open.flatMap { t -> t.tags.distinct() }.groupingBy { it }.eachCount().forEach { (tag, n) -> out[TaskFilter.WithTag(tag)] = n }
        return out
    }

    // ---- matching ----

    /** Not trashed, not in a trashed or archived list. */
    private fun visible(t: Task): Boolean {
        if (t.trashedAt != null) return false
        val p = projectById[t.projectId] ?: return true
        return p.trashedAt == null && !p.deleted && !p.closed
    }

    private fun matches(t: Task, f: TaskFilter, today: LocalDate, zone: TimeZone): Boolean = when (f) {
        is TaskFilter.Smart -> when (f.list) {
            SmartList.TRASH -> t.trashedAt != null || projectById[t.projectId]?.trashedAt != null
            SmartList.COMPLETED -> visible(t) && t.isDone
            SmartList.WONT_DO -> visible(t) && t.status == TaskStatus.WONT_DO
            SmartList.ALL -> visible(t) && t.isOpen
            SmartList.INBOX -> visible(t) && t.isOpen && t.projectId == Project.INBOX_ID
            SmartList.TODAY -> visible(t) && t.isOpen && day(t, zone)?.let { it <= today } == true
            SmartList.TOMORROW -> visible(t) && t.isOpen && day(t, zone) == today.plus(1, DateTimeUnit.DAY)
            SmartList.NEXT_7_DAYS -> visible(t) && t.isOpen && day(t, zone)?.let { it <= today.plus(6, DateTimeUnit.DAY) } == true
        }
        is TaskFilter.InProject -> t.trashedAt == null && t.projectId == f.id && t.status != TaskStatus.WONT_DO
        is TaskFilter.WithTag -> visible(t) && t.status != TaskStatus.WONT_DO && t.tags.any { it.equals(f.name, ignoreCase = true) || it.lowercase() in childTags(f.name) }
    }

    private fun childTags(name: String): Set<String> {
        val lower = name.lowercase()
        return tags.filter { it.parent?.lowercase() == lower }.mapTo(HashSet()) { it.name.lowercase() }
    }

    /** The task's day: start date (TickTick's "date"), else due; all-day dates read in the task's own zone. */
    fun day(t: Task, zone: TimeZone): LocalDate? {
        val ms = t.startDate ?: t.dueDate ?: return null
        val z = if (t.isAllDay) t.timeZone?.let(::zoneOf) ?: zone else zone
        return Instant.fromEpochMilliseconds(ms).toLocalDateTime(z).date
    }

    private fun zoneOf(id: String): TimeZone? = zoneLock.withLock {
        zones.getOrPut(id) { runCatching { TimeZone.of(id) }.getOrNull() }
    }

    private fun isOverdue(t: Task, now: Instant, today: LocalDate, zone: TimeZone): Boolean {
        val d = day(t, zone) ?: return false
        if (d < today) return true
        if (t.isAllDay || d > today) return false
        val due = t.dueDate ?: t.startDate ?: return false
        return due < now.toEpochMilliseconds()
    }

    // ---- ordering and sections ----

    private fun sort(top: List<Task>, filter: TaskFilter, zone: TimeZone): List<Task> = when {
        filter is TaskFilter.InProject -> top.sortedWith(compareBy<Task> { it.isDone }.thenBy { it.sortOrder })
        filter == TaskFilter.Smart(SmartList.COMPLETED) || filter == TaskFilter.Smart(SmartList.WONT_DO) ->
            top.sortedByDescending { it.completedAt ?: it.updatedAt }
        filter == TaskFilter.Smart(SmartList.TRASH) -> top.sortedByDescending { it.trashedAt ?: 0 }
        else -> top.map { it to day(it, zone) } // each task's day once, not per comparison
            .sortedWith(
                compareBy<Pair<Task, LocalDate?>>({ it.second == null }, { it.second }, { !it.first.isAllDay }, { it.first.startDate ?: it.first.dueDate }, { -it.first.priority }, { it.first.sortOrder }),
            )
            .map { it.first }
    }

    private fun projectSections(projectId: String, rows: List<TaskRow>): List<TaskSection> {
        val open = rows.filter { !it.task.isDone }
        val columns = columnsByProject[projectId].orEmpty().sortedBy { it.sortOrder }
        val sections = if (columns.isEmpty()) {
            listOf(TaskSection("", open))
        } else {
            val ids = columns.mapTo(HashSet()) { it.id }
            columns.map { c -> TaskSection(c.name, open.filter { it.task.columnId == c.id }) } +
                TaskSection("No section", open.filter { it.task.columnId == null || it.task.columnId !in ids })
        }
        return sections.filter { it.rows.isNotEmpty() } + completed(rows)
    }

    private fun completed(rows: List<TaskRow>): List<TaskSection> =
        rows.filter { it.task.isDone }.takeIf { it.isNotEmpty() }?.let { listOf(TaskSection("Completed", it, isCompleted = true)) }.orEmpty()

    private fun dateSections(rows: List<TaskRow>, today: LocalDate, zone: TimeZone): List<TaskSection> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val weekEnd = today.plus(6, DateTimeUnit.DAY)
        val groups = LinkedHashMap<String, MutableList<TaskRow>>()
        for (row in rows) {
            val d = day(row.task, zone)
            val title = when {
                d == null -> "No date"
                d < today -> "Overdue"
                d == today -> "Today"
                d == tomorrow -> "Tomorrow"
                d <= weekEnd -> WEEKDAYS.getValue(d.dayOfWeek)
                else -> "Later"
            }
            groups.getOrPut(title) { mutableListOf() } += row
        }
        return groups.map { (title, list) -> TaskSection(title, list) }
    }

    companion object {
        private val WEEKDAYS = mapOf(
            DayOfWeek.MONDAY to "Monday", DayOfWeek.TUESDAY to "Tuesday", DayOfWeek.WEDNESDAY to "Wednesday",
            DayOfWeek.THURSDAY to "Thursday", DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday", DayOfWeek.SUNDAY to "Sunday",
        )

        /** Reads everything the task UI needs from the store (one decode pass). */
        fun load(store: ZenStore): TaskIndex = TaskIndex(
            tasks = store.list(EntityTypes.task),
            projects = store.list(EntityTypes.project).sortedBy { it.sortOrder },
            items = store.list(EntityTypes.checklistItem),
            columns = store.list(EntityTypes.column),
            tags = store.list(EntityTypes.tag).sortedBy { it.sortOrder },
        )
    }
}
