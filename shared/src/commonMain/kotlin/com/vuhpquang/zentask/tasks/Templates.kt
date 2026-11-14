package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.model.TaskTemplate
import com.vuhpquang.zentask.store.ZenStore
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Task templates (synced): save a task as one, stamp out new tasks from it. */
class TemplateActions(
    private val store: ZenStore,
    private val actions: TaskActions,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val type = EntityTypes.taskTemplate

    /** Live templates, in their order. */
    fun list(): List<TaskTemplate> = store.list(type).filter { !it.deleted }.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))

    /** A template from [taskId]: its title, notes, priority, tags, checklist items and open subtasks (in order). */
    fun saveFrom(taskId: String, name: String): TaskTemplate? {
        val t = store.get(EntityTypes.task, taskId)?.takeIf { !it.deleted } ?: return null
        val items = store.idsWhere(EntityTypes.checklistItem, "taskId", t.id).mapNotNull { store.get(EntityTypes.checklistItem, it) }
            .filter { !it.deleted }.sortedBy { it.sortOrder }.map { it.title }
        val subs = store.idsWhere(EntityTypes.task, "parentId", t.id).mapNotNull { store.get(EntityTypes.task, it) }
            .filter { !it.deleted && it.trashedAt == null && it.status == TaskStatus.OPEN }.sortedBy { it.sortOrder }.map { it.title }
        val last = list().maxOfOrNull { it.sortOrder } ?: 0
        val tpl = TaskTemplate(
            id = Uuid.random().toString(), createdAt = wall(), name = name.trim().ifEmpty { t.title }, title = t.title,
            content = t.content.ifEmpty { t.desc }, priority = t.priority, tags = t.tags, checklist = items, subtasks = subs,
            sortOrder = last + (1L shl 32),
        )
        store.put(type, tpl)
        return tpl
    }

    /** A new task from template [id], in [projectId] (or the list being viewed, [filter]); null if it's gone. */
    fun use(id: String, filter: TaskFilter?, projectId: String? = null): Task? {
        val tpl = store.get(type, id)?.takeIf { !it.deleted } ?: return null
        var created: Task? = null
        store.inTransaction {
            val t = actions.create(tpl.title.ifBlank { tpl.name }, filter, projectId = projectId, priority = tpl.priority.takeIf { p -> p > 0 }, tags = tpl.tags)
            if (tpl.content.isNotEmpty()) actions.setContent(t.id, tpl.content)
            tpl.checklist.forEach { actions.addItem(t.id, it) }
            tpl.subtasks.forEach { actions.addSubtask(t.id, it) }
            created = t
        }
        return created?.let { store.get(EntityTypes.task, it.id) }
    }

    fun rename(id: String, name: String) {
        store.update(type, id) { it.copy(name = name.trim()) }
    }

    fun delete(id: String) = store.delete(type, id)
}
