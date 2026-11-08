package com.vuhpquang.zentask.server

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import com.vuhpquang.zentask.sync.SyncApi
import com.vuhpquang.zentask.sync.SyncEngine
import com.vuhpquang.zentask.sync.SyncStatus
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two client databases syncing through a real (in-process) server: the P1 "done" criterion. */
class SyncEndToEndTest {
    private class Device(val store: ZenStore, val engine: SyncEngine, private val clock: () -> Long) {
        suspend fun sync() = assertIs<SyncStatus.Synced>(engine.syncOnce())
        fun edit(id: String, change: (Task) -> Task) =
            store.put(EntityTypes.task, change(store.get(EntityTypes.task, id)!!))
        fun task(id: String) = store.get(EntityTypes.task, id)
    }

    private var wall = 1_800_000_000_000L

    private suspend fun ApplicationTestBuilder.device(name: String): Device {
        val token = client.loginToken(name)
        val store = ZenStore(createSqlDriver(null), wall = { wall })
        return Device(store, SyncEngine(store, SyncApi(client, "", token = { token }), wall = { wall }), { wall })
    }

    private fun ApplicationTestBuilder.server() = application { module(testConfig(), now = { wall }) }

    @Test
    fun taskCreatedOnAAppearsOnB() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "Write P1", priority = Priority.HIGH, tags = listOf("work")))
        a.sync()
        b.sync()
        val got = b.task("t1")!!
        assertEquals("Write P1", got.title)
        assertEquals(Priority.HIGH, got.priority)
        assertEquals(listOf("work"), got.tags)
        assertTrue(b.store.pending(10).isEmpty())
    }

    @Test
    fun offlineEditsOfDifferentFieldsBothSurvive() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "draft"))
        a.sync(); b.sync()
        wall += 1_000
        a.edit("t1") { it.copy(title = "final title") }
        wall += 1_000
        b.edit("t1") { it.copy(priority = Priority.MEDIUM) }
        a.sync(); b.sync(); a.sync()
        for (d in listOf(a, b)) {
            assertEquals("final title", d.task("t1")!!.title)
            assertEquals(Priority.MEDIUM, d.task("t1")!!.priority)
        }
    }

    @Test
    fun sameFieldLaterEditWinsEverywhere() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "v0"))
        a.sync(); b.sync()
        wall += 1_000
        b.edit("t1") { it.copy(title = "from B (earlier)") }
        wall += 1_000
        a.edit("t1") { it.copy(title = "from A (later)") }
        b.sync(); a.sync(); b.sync()
        assertEquals("from A (later)", a.task("t1")!!.title)
        assertEquals("from A (later)", b.task("t1")!!.title)
    }

    @Test
    fun deleteBeatsConcurrentEdit() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "doomed"))
        a.sync(); b.sync()
        wall += 1_000
        a.store.delete(EntityTypes.task, "t1")
        wall += 1_000
        b.edit("t1") { it.copy(title = "edited after the delete") }
        a.sync(); b.sync(); a.sync()
        for (d in listOf(a, b)) {
            assertTrue(d.store.list(EntityTypes.task).isEmpty())
            assertTrue(d.task("t1")!!.deleted)
        }
    }

    @Test
    fun trashIsRestorableAndSyncs() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "oops"))
        a.sync(); b.sync()
        wall += 1_000
        a.edit("t1") { it.copy(trashedAt = wall) }
        a.sync(); b.sync()
        assertEquals(wall, b.task("t1")!!.trashedAt)
        wall += 1_000
        b.edit("t1") { it.copy(trashedAt = null) }
        b.sync(); a.sync()
        assertNull(a.task("t1")!!.trashedAt)
        assertEquals(listOf("t1"), a.store.list(EntityTypes.task).map { it.id })
    }

    @Test
    fun twelveHundredRecordsReachB() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        repeat(1200) { a.store.put(EntityTypes.task, Task(id = "t$it", title = "task $it")) }
        a.sync()
        assertTrue(a.store.pending(10).isEmpty())
        b.sync()
        assertEquals(1200, b.store.list(EntityTypes.task).size)
    }

    @Test
    fun secondSyncWithNothingNewWritesNothing() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        a.store.put(EntityTypes.task, Task(id = "t1", title = "x"))
        a.sync(); b.sync()
        val before = b.store.rawRecord("task", "t1")
        val checkpoint = b.store.checkpoint
        b.sync(); a.sync(); b.sync()
        assertEquals(before, b.store.rawRecord("task", "t1"))
        assertEquals(checkpoint, b.store.checkpoint)
        assertTrue(a.store.pending(10).isEmpty())
        assertTrue(b.store.pending(10).isEmpty())
    }

    @Test
    fun serverRestoredFromBackupGetsEverythingBack() {
        val live = Files.createTempDirectory("zentask-live")
        val backup = Files.createTempDirectory("zentask-backup")
        val storeA = ZenStore(createSqlDriver(null), wall = { wall })
        lateinit var tokenA: String
        testApplication {
            application { module(testConfig(live), now = { wall }) }
            tokenA = client.loginToken("A")
            val a = Device(storeA, SyncEngine(storeA, SyncApi(client, "", { tokenA }), wall = { wall }), { wall })
            storeA.put(EntityTypes.task, Task(id = "before-backup"))
            a.sync()
        }
        live.toFile().copyRecursively(backup.toFile(), overwrite = true)
        testApplication {
            application { module(testConfig(live), now = { wall }) }
            val a = Device(storeA, SyncEngine(storeA, SyncApi(client, "", { tokenA }), wall = { wall }), { wall })
            wall += 1_000
            storeA.put(EntityTypes.task, Task(id = "after-backup"))
            a.sync()
        }
        // The server is restored from the backup: it lost "after-backup", which A has already synced.
        testApplication {
            application { module(testConfig(backup), now = { wall }) }
            val a = Device(storeA, SyncEngine(storeA, SyncApi(client, "", { tokenA }), wall = { wall }), { wall })
            a.sync()
            val b = device("B")
            b.sync()
            assertEquals(setOf("before-backup", "after-backup"), b.store.list(EntityTypes.task).map { it.id }.toSet())
        }
    }

    @Test
    fun numberCheckinsNeverMergeIntoDoneBelowGoal() = testApplication {
        server()
        val a = device("A")
        val b = device("B")
        val habit = com.vuhpquang.zentask.model.Habit(id = "h", name = "Water", type = com.vuhpquang.zentask.model.Habit.TYPE_REAL, goal = 5.0)
        a.store.put(EntityTypes.habit, habit)
        val day = kotlinx.datetime.LocalDate(2026, 11, 8)
        com.vuhpquang.zentask.habits.HabitActions(a.store) { wall }.checkIn("h", day, 3.0)
        a.sync(); b.sync()
        wall += 1_000
        com.vuhpquang.zentask.habits.HabitActions(a.store) { wall }.checkIn("h", day, 2.0) // A reaches the goal: 5, done
        wall += 1_000
        com.vuhpquang.zentask.habits.HabitActions(b.store) { wall }.checkIn("h", day, 0.5) // B, later, offline: 3.5, not done
        a.sync(); b.sync(); a.sync()
        for (d in listOf(a, b)) {
            val c = com.vuhpquang.zentask.habits.HabitActions(d.store) { wall }.checkin("h", day)!!
            assertEquals(c.value >= 5.0, c.status == 2, "status must agree with value, got $c")
        }
    }
}
