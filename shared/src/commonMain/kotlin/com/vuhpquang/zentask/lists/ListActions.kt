package com.vuhpquang.zentask.lists

import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.ProjectGroup
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.tasks.TaskActions
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** TickTick-style sort orders: Longs with wide gaps, so most moves touch one record. */
object SortOrder {
    const val STEP: Long = 1L shl 32

    /** A value between [above] and [below] (either may be absent); null when they are adjacent. */
    fun between(above: Long?, below: Long?): Long? = when {
        above == null && below == null -> 0L
        above == null -> below!! - STEP
        below == null -> above + STEP
        below - above <= 1 -> null
        else -> above + (below - above) / 2
    }
}

/**
 * Lists, folders and sections (spec §6). Trashing a list trashes its tasks with the same timestamp, so a
 * restore brings back exactly those. Every edit uses [ZenStore.update]. The Inbox cannot be renamed,
 * archived or trashed.
 */
class ListActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val projects = EntityTypes.project
    private val tasks = EntityTypes.task

    // ---- lists ----

    fun createList(name: String, color: String? = null, groupId: String? = null): Project {
        val last = store.list(projects).maxOfOrNull { it.sortOrder }
        val p = Project(
            id = Uuid.random().toString(), createdAt = wall(), name = name.trim(), color = color, groupId = groupId,
            sortOrder = SortOrder.between(last, null)!!,
        )
        store.put(projects, p)
        return p
    }

    fun rename(id: String, name: String) {
        notInbox(id, "renamed")
        store.update(projects, id) { it.copy(name = name.trim()) }
    }

    fun setColor(id: String, color: String?) {
        store.update(projects, id) { it.copy(color = color) }
    }

    fun archive(id: String) {
        notInbox(id, "archived")
        store.update(projects, id) { it.copy(closed = true) }
    }

    fun unarchive(id: String) {
        store.update(projects, id) { it.copy(closed = false) }
    }

    /** Moves the list into [groupId] (null = top level). */
    fun move(id: String, groupId: String?) {
        store.update(projects, id) { it.copy(groupId = groupId) }
    }

    /** Moves the list and its tasks to Trash (same timestamp). */
    fun trash(id: String) {
        notInbox(id, "moved to Trash")
        val now = wall()
        store.inTransaction {
            store.update(projects, id) { it.copy(trashedAt = now) }
            tasksOf(id).filter { it.trashedAt == null }.forEach { t -> store.update(tasks, t.id) { it.copy(trashedAt = now) } }
        }
    }

    /** Brings the list back with the tasks that went to Trash with it. */
    fun restore(id: String) {
        store.inTransaction {
            val p = store.get(projects, id) ?: return@inTransaction
            val stamp = p.trashedAt
            store.update(projects, id) { it.copy(trashedAt = null) }
            if (stamp != null) tasksOf(id).filter { it.trashedAt == stamp }.forEach { t -> store.update(tasks, t.id) { it.copy(trashedAt = null) } }
        }
    }

    /** Permanent delete, only from Trash: the list, the tasks trashed with it, its sections. */
    @Throws(IllegalStateException::class)
    fun deleteForever(id: String) {
        store.inTransaction {
            val p = store.get(projects, id) ?: return@inTransaction
            check(p.trashedAt != null) { "Move the list to Trash before deleting it permanently" }
            val taskActions = TaskActions(store, wall)
            tasksOf(id).filter { it.trashedAt == p.trashedAt && it.parentId == null }.forEach { taskActions.deleteForever(it.id) }
            tasksOf(id).filter { it.trashedAt == p.trashedAt }.forEach { store.delete(tasks, it.id) }
            store.idsWhere(EntityTypes.column, "projectId", id).forEach { store.delete(EntityTypes.column, it) }
            store.delete(projects, id)
        }
    }

    // ---- folders ----

    fun createFolder(name: String): ProjectGroup {
        val last = store.list(EntityTypes.projectGroup).maxOfOrNull { it.sortOrder }
        val g = ProjectGroup(id = Uuid.random().toString(), createdAt = wall(), name = name.trim(), sortOrder = SortOrder.between(last, null)!!)
        store.put(EntityTypes.projectGroup, g)
        return g
    }

    fun renameFolder(id: String, name: String) {
        store.update(EntityTypes.projectGroup, id) { it.copy(name = name.trim()) }
    }

    /** Deletes the folder; its lists move to the top level (no list is deleted). */
    fun deleteFolder(id: String) {
        store.inTransaction {
            store.idsWhere(projects, "groupId", id).forEach { pid -> store.update(projects, pid) { it.copy(groupId = null) } }
            store.delete(EntityTypes.projectGroup, id)
        }
    }

    // ---- sections ----

    fun createColumn(projectId: String, name: String): Column {
        val last = store.idsWhere(EntityTypes.column, "projectId", projectId).mapNotNull { store.get(EntityTypes.column, it) }.maxOfOrNull { it.sortOrder }
        val c = Column(id = Uuid.random().toString(), createdAt = wall(), projectId = projectId, name = name.trim(), sortOrder = SortOrder.between(last, null)!!)
        store.put(EntityTypes.column, c)
        return c
    }

    fun renameColumn(id: String, name: String) {
        store.update(EntityTypes.column, id) { it.copy(name = name.trim()) }
    }

    /** Deletes the section; its tasks move to "No section" (never deleted). */
    fun deleteColumn(id: String) {
        store.inTransaction {
            store.idsWhere(tasks, "columnId", id).forEach { tid -> store.update(tasks, tid) { it.copy(columnId = null) } }
            store.delete(EntityTypes.column, id)
        }
    }

    // ---- ordering ----

    /**
     * Places task [id] between [after] (the task above) and [before] (the task below), optionally into
     * section [columnId]. Usually one record changes; if there is no gap left, the list's tasks are
     * renumbered in one transaction.
     */
    fun reorderTask(id: String, after: String?, before: String?, columnId: String? = null) {
        store.inTransaction {
            val t = store.get(tasks, id) ?: return@inTransaction
            val above = after?.let { store.get(tasks, it) }
            val below = before?.let { store.get(tasks, it) }
            val column = columnId ?: (above ?: below)?.columnId ?: t.columnId
            val order = SortOrder.between(above?.sortOrder, below?.sortOrder)
            if (order != null) {
                store.update(tasks, id) { it.copy(sortOrder = order, columnId = column) }
                return@inTransaction
            }
            val siblings = tasksOf(t.projectId).filter { it.id != id && it.trashedAt == null }.sortedBy { it.sortOrder }.toMutableList()
            val index = above?.let { a -> siblings.indexOfFirst { it.id == a.id } + 1 } ?: 0
            siblings.add(index.coerceIn(0, siblings.size), t)
            siblings.forEachIndexed { i, s ->
                val value = i * SortOrder.STEP
                if (s.id == id) {
                    store.update(tasks, id) { it.copy(sortOrder = value, columnId = column) }
                } else if (s.sortOrder != value) {
                    store.update(tasks, s.id) { it.copy(sortOrder = value) }
                }
            }
        }
    }

    /** Places list [id] between two neighbouring lists in the sidebar (renumbers lists if needed). */
    fun reorderList(id: String, after: String?, before: String?) {
        store.inTransaction {
            val above = after?.let { store.get(projects, it) }
            val below = before?.let { store.get(projects, it) }
            val order = SortOrder.between(above?.sortOrder, below?.sortOrder)
            if (order != null) {
                store.update(projects, id) { it.copy(sortOrder = order) }
                return@inTransaction
            }
            val all = store.list(projects).filter { it.id != id }.sortedBy { it.sortOrder }.toMutableList()
            val moving = store.get(projects, id) ?: return@inTransaction
            val index = above?.let { a -> all.indexOfFirst { it.id == a.id } + 1 } ?: 0
            all.add(index.coerceIn(0, all.size), moving)
            all.forEachIndexed { i, p -> if (p.sortOrder != i * SortOrder.STEP) store.update(projects, p.id) { it.copy(sortOrder = i * SortOrder.STEP) } }
        }
    }

    // ---- helpers ----

    private fun tasksOf(projectId: String): List<Task> = store.idsWhere(tasks, "projectId", projectId).mapNotNull { store.get(tasks, it) }

    private fun notInbox(id: String, what: String) {
        require(id != Project.INBOX_ID) { "The Inbox can't be $what" }
    }
}
