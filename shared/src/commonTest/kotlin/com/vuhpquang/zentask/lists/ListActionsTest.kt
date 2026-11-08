package com.vuhpquang.zentask.lists

import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListActionsTest {
    private var wall = 1_000_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val lists = ListActions(store, wall = { wall })
    private fun project(id: String) = store.get(EntityTypes.project, id)!!
    private fun task(id: String) = store.get(EntityTypes.task, id)!!

    @Test
    fun createRenameColorArchive() {
        val a = lists.createList("Work", color = "#2E9E88")
        val b = lists.createList("Home")
        assertTrue(project(b.id).sortOrder > project(a.id).sortOrder, "new lists go to the end")
        lists.rename(a.id, "Office")
        lists.setColor(a.id, "#FF0000")
        lists.archive(a.id)
        assertEquals("Office", project(a.id).name)
        assertEquals("#FF0000", project(a.id).color)
        assertTrue(project(a.id).closed)
        lists.unarchive(a.id)
        assertTrue(!project(a.id).closed)
    }

    @Test
    fun inboxIsProtected() {
        store.put(EntityTypes.project, Project(id = Project.INBOX_ID, name = "Inbox", isInbox = true))
        assertFailsWith<IllegalArgumentException> { lists.rename(Project.INBOX_ID, "x") }
        assertFailsWith<IllegalArgumentException> { lists.archive(Project.INBOX_ID) }
        assertFailsWith<IllegalArgumentException> { lists.trash(Project.INBOX_ID) }
    }

    @Test
    fun trashAndRestoreBringBackExactlyTheTasksTrashedTogether() {
        val l = lists.createList("Trip")
        store.put(EntityTypes.task, Task(id = "a", projectId = l.id))
        store.put(EntityTypes.task, Task(id = "b", projectId = l.id, trashedAt = 5)) // trashed earlier on its own
        wall += 1_000
        lists.trash(l.id)
        assertEquals(wall, project(l.id).trashedAt)
        assertEquals(wall, task("a").trashedAt)
        assertEquals(5L, task("b").trashedAt)
        wall += 1_000
        lists.restore(l.id)
        assertNull(project(l.id).trashedAt)
        assertNull(task("a").trashedAt)
        assertEquals(5L, task("b").trashedAt)
    }

    @Test
    fun deleteForeverOnlyFromTrashAndCascades() {
        val l = lists.createList("Old")
        store.put(EntityTypes.task, Task(id = "a", projectId = l.id))
        lists.createColumn(l.id, "Doing")
        assertFailsWith<IllegalStateException> { lists.deleteForever(l.id) }
        lists.trash(l.id)
        lists.deleteForever(l.id)
        assertTrue(project(l.id).deleted)
        assertTrue(task("a").deleted)
        assertTrue(store.list(EntityTypes.column).isEmpty())
    }

    @Test
    fun foldersAndMoving() {
        val f = lists.createFolder("Personal")
        val l = lists.createList("Home", groupId = f.id)
        assertEquals(f.id, project(l.id).groupId)
        lists.renameFolder(f.id, "Me")
        assertEquals("Me", store.get(EntityTypes.projectGroup, f.id)!!.name)
        lists.move(l.id, null)
        assertNull(project(l.id).groupId)
        lists.move(l.id, f.id)
        lists.deleteFolder(f.id)
        assertNull(project(l.id).groupId, "lists leave the deleted folder")
        assertTrue(store.get(EntityTypes.projectGroup, f.id)!!.deleted)
    }

    @Test
    fun columnsNeverDeleteTasks() {
        val l = lists.createList("Board")
        val c = lists.createColumn(l.id, "Todo")
        lists.renameColumn(c.id, "To do")
        store.put(EntityTypes.task, Task(id = "a", projectId = l.id, columnId = c.id))
        lists.deleteColumn(c.id)
        assertNull(task("a").columnId)
        assertTrue(!task("a").deleted)
        assertTrue(store.get(EntityTypes.column, c.id)!!.deleted)
    }

    @Test
    fun reorderTasksBetweenNeighboursAndRenumberWhenNoGap() {
        listOf("a" to 0L, "b" to 1L, "c" to 2L).forEach { (id, o) -> store.put(EntityTypes.task, Task(id = id, projectId = "p", sortOrder = o)) }
        lists.reorderTask("c", after = "a", before = "b") // no gap between 0 and 1: renumber
        assertEquals(listOf("a", "c", "b"), store.list(EntityTypes.task).sortedBy { it.sortOrder }.map { it.id })
        lists.reorderTask("a", after = "b", before = null)
        assertEquals(listOf("c", "b", "a"), store.list(EntityTypes.task).sortedBy { it.sortOrder }.map { it.id })
    }

    @Test
    fun reorderIntoAnotherColumn() {
        store.put(EntityTypes.column, Column(id = "c1", projectId = "p"))
        store.put(EntityTypes.task, Task(id = "a", projectId = "p", sortOrder = 0))
        store.put(EntityTypes.task, Task(id = "b", projectId = "p", sortOrder = 1L shl 32, columnId = "c1"))
        lists.reorderTask("a", after = "b", before = null, columnId = "c1")
        assertEquals("c1", task("a").columnId)
        assertTrue(task("a").sortOrder > task("b").sortOrder)
    }

    @Test
    fun concurrentReordersOnTwoDevicesBothSurvive() {
        listOf("a" to 0L, "b" to (10L shl 32), "c" to (20L shl 32), "d" to (30L shl 32)).forEach { (id, o) -> store.put(EntityTypes.task, Task(id = id, projectId = "p", sortOrder = o)) }
        val other = ZenStore(createSqlDriver(null), wall = { wall })
        other.applyRemote(store.pending(100).map { it.record })
        wall += 1_000
        lists.reorderTask("d", after = null, before = "a") // device 1: d to the top
        ListActions(other, wall = { wall }).reorderTask("a", after = "c", before = null) // device 2: a after c (gap exists)
        other.applyRemote(store.pending(100).map { it.record })
        store.applyRemote(other.pending(100).map { it.record })
        val order = { s: ZenStore -> s.list(EntityTypes.task).sortedBy { it.sortOrder }.map { it.id } }
        assertEquals(order(store), order(other))
        assertEquals("d", order(store).first())
    }

    @Test
    fun sortOrderBetween() {
        assertEquals(0L, SortOrder.between(null, null))
        assertEquals(10L + SortOrder.STEP, SortOrder.between(10, null))
        assertEquals(10L - SortOrder.STEP, SortOrder.between(null, 10))
        assertEquals(5L, SortOrder.between(0, 10))
        assertNull(SortOrder.between(4, 5))
    }
}
