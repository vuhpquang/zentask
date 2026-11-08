package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickTickSyncTest {
    private var wall = 1_800_000_000_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val sync = TickTickSync(store, wall = { wall })

    private fun task(id: String, title: String = "Task $id", priority: Int = 0, status: Int = 0) =
        TtTask(id = id, projectId = "p1", title = title, priority = priority, status = status)

    private fun snap(vararg tasks: TtTask, habits: List<TtHabit>? = null) = TickTickSnapshot(
        inboxId = "inbox1",
        projects = listOf(TtProject(id = "p1", name = "Works")),
        tasks = tasks.toList(),
        habits = habits.orEmpty(),
        habitsAvailable = habits != null,
    )

    private fun tasks() = store.list(EntityTypes.task)
    private fun local(id: String) = store.get(EntityTypes.task, "tt_$id")!!

    @Test
    fun firstSyncCreatesEverything() {
        val plan = sync.apply(snap(task("a"), task("b")))
        assertEquals(4, plan.created, "inbox + Works + 2 tasks")
        assertEquals(0, plan.updated)
        assertEquals(setOf("tt_a", "tt_b"), tasks().map { it.id }.toSet())
        assertEquals("Works", store.get(EntityTypes.project, "tt_p1")!!.name)
        assertNotNull(sync.lastSyncAt)
    }

    @Test
    fun secondIdenticalSyncChangesNothing() {
        sync.apply(snap(task("a"), task("b")))
        store.markSynced(store.pending(1000))
        wall += 60_000
        val plan = sync.apply(snap(task("a"), task("b")))
        assertEquals(SyncPlan(0, 0, 0, 0, 0, false), plan)
        assertTrue(store.pending(1000).isEmpty())
    }

    @Test
    fun zentaskEditOfUntouchedFieldSurvives() {
        sync.apply(snap(task("a", title = "From TickTick")))
        wall += 1_000
        store.update(EntityTypes.task, "tt_a") { it.copy(title = "Renamed in ZenTask", priority = Priority.HIGH) }
        wall += 1_000
        val plan = sync.apply(snap(task("a", title = "From TickTick")))
        assertEquals(0, plan.updated)
        assertEquals("Renamed in ZenTask", local("a").title)
        assertEquals(Priority.HIGH, local("a").priority)
    }

    @Test
    fun tickTickChangeWinsOverZenTaskEdit() {
        sync.apply(snap(task("a", title = "v1", priority = 0)))
        wall += 1_000
        store.update(EntityTypes.task, "tt_a") { it.copy(title = "ZenTask title", priority = Priority.LOW) }
        wall += 1_000
        val plan = sync.apply(snap(task("a", title = "TickTick v2", priority = 0)))
        assertEquals(1, plan.updated)
        assertEquals("TickTick v2", local("a").title)
        assertEquals(Priority.LOW, local("a").priority, "TickTick didn't change priority: ZenTask's edit stays")
    }

    @Test
    fun disappearedTaskGoesToTrashNotDeleted() {
        sync.apply(snap(task("a"), task("b")))
        wall += 1_000
        val plan = sync.apply(snap(task("a")))
        assertEquals(1, plan.trashed)
        assertEquals(wall, local("b").trashedAt)
        assertTrue(!local("b").deleted)
        val again = sync.apply(snap(task("a")))
        assertEquals(0, again.trashed, "already trashed by a previous sync")
    }

    @Test
    fun reappearedTaskIsRestored() {
        sync.apply(snap(task("a"), task("b")))
        sync.apply(snap(task("a")))
        val plan = sync.apply(snap(task("a"), task("b")))
        assertEquals(1, plan.restored)
        assertNull(local("b").trashedAt)
    }

    @Test
    fun zentaskTrashOfTickTickTaskIsKept() {
        sync.apply(snap(task("a")))
        wall += 1_000
        store.update(EntityTypes.task, "tt_a") { it.copy(trashedAt = wall) }
        sync.apply(snap(task("a")))
        assertEquals(wall, local("a").trashedAt)
    }

    @Test
    fun zentaskOnlyRecordsAreNeverTouched() {
        store.put(EntityTypes.task, Task(id = "mine", title = "Only in ZenTask"))
        sync.apply(snap(task("a")))
        sync.apply(snap())
        val mine = store.get(EntityTypes.task, "mine")!!
        assertEquals("Only in ZenTask", mine.title)
        assertNull(mine.trashedAt)
    }

    @Test
    fun massTrashNeedsConfirmation() {
        sync.apply(snap(*Array(100) { task("t$it") }))
        val preview = sync.preview(snap())
        assertTrue(preview.needsConfirmation)
        assertEquals(100, preview.trashed)
        assertFailsWith<MassTrashException> { sync.apply(snap()) }
        assertEquals(100, tasks().count { it.trashedAt == null }, "refused: nothing trashed")
        val plan = sync.apply(snap(), confirmMassTrash = true)
        assertEquals(100, plan.trashed)
        assertTrue(tasks().all { it.trashedAt != null })
    }

    @Test
    fun smallRemovalNeedsNoConfirmation() {
        sync.apply(snap(*Array(100) { task("t$it") }))
        assertEquals(5, sync.apply(snap(*Array(95) { task("t$it") })).trashed)
    }

    @Test
    fun habitDisappearanceArchivesOnlyWhenHabitsWereFetched() {
        sync.apply(snap(habits = listOf(TtHabit(id = "h1", name = "Run"))))
        sync.apply(snap(habits = null))
        assertNull(store.get(EntityTypes.habit, "tt_h1")!!.archivedAt, "habits unavailable: leave alone")
        wall += 1_000
        sync.apply(snap(habits = emptyList()))
        assertEquals(wall, store.get(EntityTypes.habit, "tt_h1")!!.archivedAt)
    }

    @Test
    fun wontDoTasksAreKeptWhenWontDoWasNotFetched() {
        sync.apply(snap(task("a", status = -1)).copy(wontDoAvailable = true))
        val plan = sync.apply(snap().copy(wontDoAvailable = false))
        assertEquals(0, plan.trashed)
        assertNull(local("a").trashedAt)
    }

    @Test
    fun checklistItemRemovedInTickTickIsRemovedHere() {
        val withItems = task("a").copy(items = listOf(TtItem(id = "i1", title = "one"), TtItem(id = "i2", title = "two")))
        sync.apply(snap(withItems))
        sync.apply(snap(task("a").copy(items = listOf(TtItem(id = "i1", title = "one")))))
        assertEquals(listOf("tt_i1"), store.list(EntityTypes.checklistItem).map { it.id })
    }

    @Test
    fun previewDoesNotWrite() {
        val plan = sync.preview(snap(task("a")))
        assertEquals(3, plan.created)
        assertTrue(tasks().isEmpty())
        assertTrue(store.pending(10).isEmpty())
        assertNull(sync.lastSyncAt)
    }

    @Test
    fun deviceWithoutBaselineAdoptsInsteadOfOverwriting() {
        // Device A imports, then the owner edits in ZenTask.
        sync.apply(snap(task("x", title = "TT title"), task("y")))
        wall += 1_000
        store.update(EntityTypes.task, "tt_x") { it.copy(title = "Renamed in ZenTask", trashedAt = wall) }
        store.delete(EntityTypes.task, "tt_y")
        // Device B receives those records through server sync, then syncs TickTick for the first time.
        val storeB = ZenStore(createSqlDriver(null), wall = { wall })
        storeB.applyRemote(listOf("project" to "inbox", "project" to "tt_p1", "task" to "tt_x", "task" to "tt_y").map { (e, id) -> store.rawRecord(e, id)!! })
        val plan = TickTickSync(storeB, wall = { wall }).apply(snap(task("x", title = "TT title"), task("y")))
        assertEquals(0, plan.updated)
        assertEquals(0, plan.restored)
        val x = storeB.get(EntityTypes.task, "tt_x")!!
        assertEquals("Renamed in ZenTask", x.title)
        assertEquals(wall, x.trashedAt)
        assertTrue(storeB.get(EntityTypes.task, "tt_y")!!.deleted)
        assertTrue(storeB.pending(100).isEmpty(), "B wrote nothing")
    }

    @Test
    fun missingKeysAreNotTreatedAsCleared() {
        sync.apply(snap(task("a").copy(tags = listOf("work"), items = listOf(TtItem(id = "i1", title = "one")))))
        val plan = sync.apply(snap(task("a").copy(tags = null, items = null)))
        assertEquals(0, plan.trashed)
        assertEquals(listOf("work"), local("a").tags)
        assertEquals(listOf("tt_i1"), store.list(EntityTypes.checklistItem).map { it.id })
    }

    @Test
    fun thinTaskDoesNotClearFields() {
        sync.apply(snap(task("a").copy(dueDate = "2026-10-30T04:00:00.000+0000", content = "notes")))
        val thin = snap(TtTask(id = "a", projectId = "p1", title = "Task a", status = 2)).copy(thinTaskIds = setOf("a"))
        sync.apply(thin)
        assertEquals(1_793_332_800_000L, local("a").dueDate)
        assertEquals("notes", local("a").content)
        assertEquals(2, local("a").status, "fields that are present still update")
    }

    @Test
    fun truncatedCompletedListDoesNotTrashCompletedTasks() {
        sync.apply(snap(task("open1"), task("done1", status = 2)))
        val plan = sync.apply(snap(task("open1")).copy(completedTruncated = true))
        assertEquals(0, plan.trashed)
        assertNull(local("done1").trashedAt)
    }

    @Test
    fun deletedChecklistItemThatReturnsIsRecreated() {
        sync.apply(snap(task("a").copy(items = listOf(TtItem(id = "i1", title = "one"), TtItem(id = "i2", title = "two")))))
        sync.apply(snap(task("a").copy(items = listOf(TtItem(id = "i1", title = "one")))))
        sync.apply(snap(task("a").copy(items = listOf(TtItem(id = "i1", title = "one"), TtItem(id = "i2", title = "two")))))
        assertEquals(setOf("tt_i1", "tt_i2"), store.list(EntityTypes.checklistItem).map { it.id }.toSet())
    }
}
