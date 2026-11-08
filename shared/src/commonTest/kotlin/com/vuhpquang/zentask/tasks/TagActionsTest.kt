package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagActionsTest {
    private val store = ZenStore(createSqlDriver(null), wall = { 1L })
    private val tags = TagActions(store)

    private fun seed() {
        store.put(EntityTypes.tag, Tag(id = "t1", name = "work", label = "Work"))
        store.put(EntityTypes.tag, Tag(id = "t2", name = "job", label = "Job"))
        store.put(EntityTypes.task, Task(id = "a", tags = listOf("work", "home")))
        store.put(EntityTypes.task, Task(id = "b", tags = listOf("job")))
    }

    @Test
    fun renameRewritesTasks() {
        seed()
        tags.rename("work", "Office")
        assertEquals(listOf("office", "home"), store.get(EntityTypes.task, "a")!!.tags)
        val tag = store.get(EntityTypes.tag, "t1")!!
        assertEquals("office", tag.name)
        assertEquals("Office", tag.label)
    }

    @Test
    fun renameOntoExistingMerges() {
        seed()
        tags.rename("job", "work")
        assertEquals(listOf("work"), store.get(EntityTypes.task, "b")!!.tags)
        assertTrue(store.get(EntityTypes.tag, "t2")!!.deleted)
        assertEquals(listOf("work"), store.list(EntityTypes.tag).map { it.name })
    }

    @Test
    fun deleteRemovesFromTasksOnly() {
        seed()
        tags.delete("work")
        assertEquals(listOf("home"), store.get(EntityTypes.task, "a")!!.tags)
        assertTrue(store.list(EntityTypes.task).size == 2)
        assertTrue(store.get(EntityTypes.tag, "t1")!!.deleted)
    }

    @Test
    fun colorAndParent() {
        seed()
        tags.setColor("work", "#2E9E88")
        tags.setParent("job", "work")
        assertEquals("#2E9E88", store.get(EntityTypes.tag, "t1")!!.color)
        assertEquals("work", store.get(EntityTypes.tag, "t2")!!.parent)
    }
}
