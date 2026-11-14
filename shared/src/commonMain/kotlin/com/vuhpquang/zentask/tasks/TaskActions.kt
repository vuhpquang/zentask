package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
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

    /** Reminder triggers for a new dated task (all-day or not); the apps set this from the notification settings. */
    var defaultReminders: (allDay: Boolean) -> List<String> = { emptyList() }

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
        val project = projectId ?: (filter as? TaskFilter.InProject)?.id ?: Project.INBOX_ID
        // New tasks go to the top of their list with their own sort order (no ties to renumber later).
        val top = store.idsWhere(tasks, "projectId", project).mapNotNull { store.get(tasks, it)?.sortOrder }.minOrNull()
        val task = Task(
            id = Uuid.random().toString(),
            createdAt = now,
            title = title.trim(),
            sortOrder = top?.let { it - (1L shl 32) } ?: 0,
            projectId = project,
            priority = priority ?: 0,
            tags = allTags,
            dueDate = due ?: filterDue,
            isAllDay = if (due != null) allDay else filterDue != null,
            timeZone = if (due != null || filterDue != null) z.id else null,
        ).let { t -> if (t.dueDate == null) t else t.copy(reminders = defaultReminders(t.isAllDay).filter(String::isNotBlank).distinct()) }
        store.inTransaction {
            ensureTags(allTags)
            store.put(tasks, task)
        }
        return task
    }

    @Throws(IllegalStateException::class)
    fun addSubtask(parentId: String, title: String): Task {
        val parent = store.get(tasks, parentId) ?: error("no task $parentId")
        val last = store.idsWhere(tasks, "parentId", parentId).mapNotNull { store.get(tasks, it)?.sortOrder }.maxOrNull()
        val t = Task(
            id = Uuid.random().toString(), createdAt = wall(), title = title.trim(), projectId = parent.projectId, parentId = parentId,
            sortOrder = last?.let { it + (1L shl 32) } ?: 0,
        )
        store.put(tasks, t)
        return t
    }

    /**
     * A new open copy just below the original: same list, fields, tags, checklist (unticked) and
     * subtasks. The copy is ZenTask's own (no TickTick id).
     */
    fun duplicate(id: String): Task {
        lateinit var copy: Task
        store.inTransaction {
            val t = store.get(tasks, id) ?: error("no task $id")
            val now = wall()
            copy = t.copy(
                id = Uuid.random().toString(), createdAt = now, updatedAt = now, status = TaskStatus.OPEN, completedAt = null,
                ticktickId = null, repeatTaskId = null, trashedAt = null, pinnedAt = null, sortOrder = t.sortOrder + 1,
            )
            store.put(tasks, copy)
            store.idsWhere(EntityTypes.checklistItem, "taskId", id).mapNotNull { store.get(EntityTypes.checklistItem, it) }.forEach { item ->
                store.put(
                    EntityTypes.checklistItem,
                    item.copy(id = Uuid.random().toString(), createdAt = now, taskId = copy.id, status = TaskStatus.OPEN, completedAt = null, ticktickId = null),
                )
            }
            children(id).filter { it.trashedAt == null }.forEach { sub ->
                store.put(
                    tasks,
                    sub.copy(id = Uuid.random().toString(), createdAt = now, parentId = copy.id, status = TaskStatus.OPEN, completedAt = null, ticktickId = null),
                )
            }
        }
        return copy
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
                val zoneForTask = t.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone()
                // From completion: today's date, but the task's own time of day (midnight for all-day).
                val from = if (fromCompletion) {
                    val time = Instant.fromEpochMilliseconds(base).toLocalDateTime(zoneForTask).time
                    LocalDateTime(Instant.fromEpochMilliseconds(now).toLocalDateTime(zoneForTask).date, time).toInstant(zoneForTask).toEpochMilliseconds()
                } else {
                    base
                }
                val done = store.idsWhere(tasks, "repeatTaskId", id).size
                val next = runCatching { Recurrence.next(rule, Instant.fromEpochMilliseconds(from), zoneForTask, done) }.getOrNull()
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

    /**
     * Permanent delete (tombstone), only for a task already in Trash; takes the subtasks that went to
     * Trash with it (not ones restored since) and its checklist.
     */
    @Throws(IllegalStateException::class)
    fun deleteForever(id: String) {
        store.inTransaction {
            val t = store.get(tasks, id) ?: return@inTransaction
            check(t.trashedAt != null) { "Move the task to Trash before deleting it permanently" }
            (children(id).filter { it.trashedAt == t.trashedAt } + t).forEach { victim ->
                store.idsWhere(EntityTypes.checklistItem, "taskId", victim.id).forEach { store.delete(EntityTypes.checklistItem, it) }
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
            firstDateDefaults(it, it.copy(dueDate = due, startDate = null, isAllDay = due != null && allDay, timeZone = if (due != null) it.timeZone ?: z else it.timeZone))
        }
    }

    /**
     * Moves the task to the day starting at [day] (a local midnight), keeping its time of day and its
     * length (a timed block or an all-day range). A task with no date gets that day, all-day.
     */
    fun moveToDay(id: String, day: Long) {
        val t = store.get(tasks, id) ?: return
        val anchor = t.startDate ?: t.dueDate ?: return setDue(id, day, allDay = true)
        val z = t.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone()
        val from = Instant.fromEpochMilliseconds(anchor).toLocalDateTime(z).date
        val to = Instant.fromEpochMilliseconds(day).toLocalDateTime(z).date
        val days = (to.toEpochDays() - from.toEpochDays()).toLong()
        if (days == 0L) return
        fun shift(ms: Long?) = ms?.let { Instant.fromEpochMilliseconds(it).plus(days, DateTimeUnit.DAY, z).toEpochMilliseconds() }
        store.update(tasks, id) { it.copy(startDate = shift(it.startDate), dueDate = shift(it.dueDate)) }
    }

    /** Moves each task to [today] (a local midnight) with [moveToDay]: times and lengths stay. */
    fun moveAllToDay(ids: List<String>, today: Long) {
        store.inTransaction { ids.forEach { moveToDay(it, today) } }
    }

    /** A task getting its first date (with no reminders yet) gets the default reminder, like a new dated task. */
    private fun firstDateDefaults(before: Task, after: Task): Task {
        val hadDate = before.startDate != null || before.dueDate != null
        val hasDate = after.startDate != null || after.dueDate != null
        if (hadDate || !hasDate || before.reminders.isNotEmpty()) return after
        return after.copy(reminders = defaultReminders(after.isAllDay).filter(String::isNotBlank).distinct())
    }

    /** A timed block from [start] to [end] (e.g. dragged out on the calendar). */
    fun setSpan(id: String, start: Long, end: Long) {
        val z = zone().id
        store.update(tasks, id) { firstDateDefaults(it, it.copy(startDate = start, dueDate = maxOf(end, start), isAllDay = false, timeZone = it.timeZone ?: z)) }
    }

    /**
     * An all-day range from the day starting at [startDay] through the day starting at [lastDay] (local
     * midnights, both included). Stored like TickTick: the due date is the midnight after the last day.
     */
    fun setAllDaySpan(id: String, startDay: Long, lastDay: Long) {
        val z = zone()
        val last = Instant.fromEpochMilliseconds(maxOf(lastDay, startDay)).toLocalDateTime(z).date
        val end = last.plus(1, DateTimeUnit.DAY).atStartOfDayIn(z).toEpochMilliseconds()
        store.update(tasks, id) { firstDateDefaults(it, it.copy(startDate = startDay, dueDate = end, isAllDay = true, timeZone = it.timeZone ?: z.id)) }
    }

    /** Sets (or clears, with null) the repeat rule; [from] is "due" or "completion". */
    fun setRepeat(id: String, rule: String?, from: String?) {
        store.update(tasks, id) { it.copy(repeatRule = rule?.takeIf { r -> r.isNotBlank() }, repeatFrom = if (rule.isNullOrBlank()) null else from ?: "due") }
    }

    /** Reminder triggers (`TRIGGER:-PT15M`…), duplicates dropped. */
    fun setReminders(id: String, triggers: List<String>) {
        store.update(tasks, id) { it.copy(reminders = triggers.map(String::trim).filter(String::isNotEmpty).distinct()) }
    }

    fun pin(id: String) {
        val now = wall()
        store.update(tasks, id) { it.copy(pinnedAt = now) }
    }

    fun unpin(id: String) {
        store.update(tasks, id) { it.copy(pinnedAt = null) }
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
        val existing = store.idsWhere(EntityTypes.checklistItem, "taskId", taskId).mapNotNull { store.get(EntityTypes.checklistItem, it) }
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

    private fun children(id: String): List<Task> = store.idsWhere(tasks, "parentId", id).mapNotNull { store.get(tasks, it) }

    /** Lowercase, trimmed, without `#`, de-duplicated, order kept. */
    private fun normalize(tags: List<String>): List<String> =
        tags.map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** Creates a Tag record for tag names that don't have one yet. */
    private fun ensureTags(names: List<String>) {
        if (names.isEmpty()) return
        val known = store.list(EntityTypes.tag).mapTo(HashSet()) { it.name.lowercase() }
        val now = wall()
        names.filter { it !in known }.forEach { name ->
            // "tag_<name>" may belong to a tag that was renamed since: never overwrite it.
            val id = "tag_$name".takeIf { store.rawRecord(EntityTypes.tag.name, it) == null } ?: "tag_${Uuid.random()}"
            store.put(EntityTypes.tag, Tag(id = id, createdAt = now, name = name, label = name, parent = name.substringBeforeLast('/', "").ifEmpty { null }))
        }
    }
}
