package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Everything the UI (and later the MCP server) does to tasks. Every edit goes through
 * [ZenStore.update], so a stale copy never reverts another device's change. Multi-record changes
 * (recurring completion, trash cascades) run in one transaction.
 */
class TaskActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val tasks = EntityTypes.task

    // ---- create ----

    /**
     * A new open task. Defaults come from the list being viewed ([filter]): Today/Tomorrow → due that day
     * (all-day), a list → that list, a tag → that tag. Explicit arguments (from quick add) win.
     */
    fun create(
        title: String,
        filter: TaskFilter?,
        projectId: String? = null,
        priority: Int? = null,
        tags: List<String> = emptyList(),
        due: Long? = null,
        allDay: Boolean = true,
    ): Task {
        val now = wall()
        val z = zone()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(z).date
        val filterDue = when (filter) {
            TaskFilter.Smart(SmartList.TODAY) -> today
            TaskFilter.Smart(SmartList.TOMORROW) -> today.plus(1, DateTimeUnit.DAY)
            else -> null
        }?.atStartOfDayIn(z)?.toEpochMilliseconds()
        val filterTags = (filter as? TaskFilter.WithTag)?.let { listOf(it.name) }.orEmpty()
        val allTags = normalize(tags + filterTags)
        val task = Task(
            id = Uuid.random().toString(),
            createdAt = now,
            title = title.trim(),
            projectId = projectId ?: (filter as? TaskFilter.InProject)?.id ?: Project.INBOX_ID,
            priority = priority ?: 0,
            tags = allTags,
            dueDate = due ?: filterDue,
            isAllDay = if (due != null) allDay else filterDue != null,
            timeZone = if (due != null || filterDue != null) z.id else null,
        )
        store.inTransaction {
            ensureTags(allTags)
            store.put(tasks, task)
        }
        return task
    }

    fun addSubtask(parentId: String, title: String): Task {
        val parent = store.get(tasks, parentId) ?: error("no task $parentId")
        val t = Task(id = Uuid.random().toString(), createdAt = wall(), title = title.trim(), projectId = parent.projectId, parentId = parentId)
        store.put(tasks, t)
        return t
    }

    // ---- status ----

    /**
     * Completes a task. A repeating task (supported rule, not ended) instead gets a completed copy
     * (`repeatTaskId` → original) and moves to its next occurrence, staying open (spec §7.1).
     */
    fun complete(id: String) {
        store.inTransaction {
            val t = store.get(tasks, id) ?: return@inTransaction
            val now = wall()
            val rule = t.repeatRule
            val base = t.dueDate ?: t.startDate
            if (rule != null && base != null && Recurrence.isSupported(rule)) {
                val fromCompletion = t.repeatFrom == "completion" || t.repeatFrom == "1"
                val from = if (fromCompletion) now else base
                val done = store.list(tasks).count { it.repeatTaskId == id }
                val zoneForTask = t.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone()
                val next = Recurrence.next(rule, Instant.fromEpochMilliseconds(from), zoneForTask, done)
                if (next != null) {
                    store.put(
                        tasks,
                        t.copy(
                            id = Uuid.random().toString(), createdAt = now, status = TaskStatus.COMPLETED, completedAt = now,
                            repeatTaskId = id, repeatRule = null, repeatFrom = null, ticktickId = null, reminders = emptyList(),
                        ),
                    )
                    val shift = next.toEpochMilliseconds() - base
                    store.update(tasks, id) { it.copy(dueDate = it.dueDate?.plus(shift), startDate = it.startDate?.plus(shift)) }
                    return@inTransaction
                }
            }
            store.update(tasks, id) { it.copy(status = TaskStatus.COMPLETED, completedAt = now) }
        }
    }

    fun uncomplete(id: String) {
        store.update(tasks, id) { it.copy(status = TaskStatus.OPEN, completedAt = null) }
    }

    fun wontDo(id: String) {
        val now = wall()
        store.update(tasks, id) { it.copy(status = TaskStatus.WONT_DO, completedAt = now) }
    }

    // ---- trash ----

    /** Moves the task and its subtasks to Trash. */
    fun trash(id: String) {
        val now = wall()
        store.inTransaction {
            store.update(tasks, id) { it.copy(trashedAt = now) }
            children(id).filter { it.trashedAt == null }.forEach { c -> store.update(tasks, c.id) { it.copy(trashedAt = now) } }
        }
    }

    /** Brings the task back, with the subtasks that went to Trash together with it. */
    fun restore(id: String) {
        store.inTransaction {
            val t = store.get(tasks, id) ?: return@inTransaction
            val when_ = t.trashedAt
            store.update(tasks, id) { it.copy(trashedAt = null) }
            children(id).filter { when_ != null && it.trashedAt == when_ }.forEach { c -> store.update(tasks, c.id) { it.copy(trashedAt = null) } }
        }
    }

    /** Permanent delete (tombstone), only for a task already in Trash; takes its subtasks and checklist. */
    fun deleteForever(id: String) {
        store.inTransaction {
            val t = store.get(tasks, id) ?: return@inTransaction
            check(t.trashedAt != null) { "Move the task to Trash before deleting it permanently" }
            (children(id) + t).forEach { victim ->
                store.list(EntityTypes.checklistItem).filter { it.taskId == victim.id }.forEach { store.delete(EntityTypes.checklistItem, it.id) }
                store.delete(tasks, victim.id)
            }
        }
    }

    // ---- fields ----

    fun rename(id: String, title: String) {
        store.update(tasks, id) { it.copy(title = title.trim()) }
    }

    fun setContent(id: String, content: String) {
        store.update(tasks, id) { it.copy(content = content) }
    }

    fun setPriority(id: String, priority: Int) {
        store.update(tasks, id) { it.copy(priority = priority) }
    }

    /** Sets (or clears, with null) the date; a single date is both start and due. */
    fun setDue(id: String, due: Long?, allDay: Boolean) {
        val z = zone().id
        store.update(tasks, id) {
            it.copy(dueDate = due, startDate = null, isAllDay = due != null && allDay, timeZone = if (due != null) it.timeZone ?: z else it.timeZone)
        }
    }

    fun setTags(id: String, tags: List<String>) {
        val clean = normalize(tags)
        store.inTransaction {
            ensureTags(clean)
            store.update(tasks, id) { it.copy(tags = clean) }
        }
    }

    /** Moves the task (and its subtasks) to another list. */
    fun move(id: String, projectId: String) {
        store.inTransaction {
            store.update(tasks, id) { it.copy(projectId = projectId, columnId = null) }
            children(id).forEach { c -> store.update(tasks, c.id) { it.copy(projectId = projectId, columnId = null) } }
        }
    }

    // ---- checklist ----

    fun addItem(taskId: String, title: String): ChecklistItem {
        val existing = store.list(EntityTypes.checklistItem).filter { it.taskId == taskId }
        val item = ChecklistItem(
            id = Uuid.random().toString(), createdAt = wall(), taskId = taskId, title = title.trim(),
            sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        store.inTransaction {
            store.put(EntityTypes.checklistItem, item)
            store.update(tasks, taskId) { it.copy(kind = Task.KIND_CHECKLIST) }
        }
        return item
    }

    fun toggleItem(itemId: String) {
        val now = wall()
        store.update(EntityTypes.checklistItem, itemId) {
            val done = it.status == TaskStatus.COMPLETED || it.status == 1
            it.copy(status = if (done) TaskStatus.OPEN else TaskStatus.COMPLETED, completedAt = if (done) null else now)
        }
    }

    fun renameItem(itemId: String, title: String) {
        store.update(EntityTypes.checklistItem, itemId) { it.copy(title = title.trim()) }
    }

    fun deleteItem(itemId: String) = store.delete(EntityTypes.checklistItem, itemId)

    // ---- helpers ----

    private fun children(id: String): List<Task> = store.list(tasks).filter { it.parentId == id }

    /** Lowercase, trimmed, without `#`, de-duplicated, order kept. */
    private fun normalize(tags: List<String>): List<String> =
        tags.map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** Creates a Tag record for tag names that don't have one yet. */
    private fun ensureTags(names: List<String>) {
        if (names.isEmpty()) return
        val known = store.list(EntityTypes.tag).mapTo(HashSet()) { it.name.lowercase() }
        val now = wall()
        names.filter { it !in known }.forEach { name ->
            store.put(EntityTypes.tag, Tag(id = "tag_$name", createdAt = now, name = name, label = name, parent = name.substringBeforeLast('/', "").ifEmpty { null }))
        }
    }
}
