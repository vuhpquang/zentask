package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.store.ZenStore

/**
 * Tag maintenance. Tags are referenced by name from tasks (`Task.tags`), so renaming rewrites every
 * task that carries the tag; renaming onto an existing tag merges the two. All edits use update().
 */
class TagActions(private val store: ZenStore) {
    fun rename(old: String, new: String) {
        val from = old.trim().lowercase()
        val to = new.trim().removePrefix("#").lowercase()
        if (to.isEmpty() || from == to) return
        store.inTransaction {
            val all = store.list(EntityTypes.tag)
            val source = all.firstOrNull { it.name.lowercase() == from }
            val target = all.firstOrNull { it.name.lowercase() == to }
            store.list(EntityTypes.task).filter { t -> t.tags.any { it.lowercase() == from } }.forEach { t ->
                store.update(EntityTypes.task, t.id) { cur -> cur.copy(tags = cur.tags.map { if (it.lowercase() == from) to else it }.distinct()) }
            }
            all.filter { it.parent?.lowercase() == from }.forEach { child ->
                store.update(EntityTypes.tag, child.id) { it.copy(parent = to) }
            }
            if (source != null) {
                if (target != null) {
                    store.delete(EntityTypes.tag, source.id)
                } else {
                    store.update(EntityTypes.tag, source.id) { it.copy(name = to, label = new.trim().removePrefix("#")) }
                }
            }
        }
    }

    fun setColor(name: String, color: String?) {
        tag(name)?.let { t -> store.update(EntityTypes.tag, t.id) { it.copy(color = color) } }
    }

    fun setParent(name: String, parent: String?) {
        tag(name)?.let { t -> store.update(EntityTypes.tag, t.id) { it.copy(parent = parent?.lowercase()) } }
    }

    /** Removes the tag from every task and deletes the tag itself; no task is trashed. */
    fun delete(name: String) {
        val key = name.trim().lowercase()
        store.inTransaction {
            store.list(EntityTypes.task).filter { t -> t.tags.any { it.lowercase() == key } }.forEach { t ->
                store.update(EntityTypes.task, t.id) { cur -> cur.copy(tags = cur.tags.filterNot { it.lowercase() == key }) }
            }
            tag(key)?.let { store.delete(EntityTypes.tag, it.id) }
        }
    }

    private fun tag(name: String) = store.list(EntityTypes.tag).firstOrNull { it.name.lowercase() == name.trim().lowercase() }
}
