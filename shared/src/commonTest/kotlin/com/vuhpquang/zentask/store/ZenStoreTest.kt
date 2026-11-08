package com.vuhpquang.zentask.store

import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.SyncRecord
import com.vuhpquang.zentask.sync.isDeleted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZenStoreTest {
    private var wall = 1_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val task = EntityTypes.task

    @Test
    fun putThenGetAndList() {
        store.put(task, Task(id = "t1", title = "Buy milk"))
        val got = assertNotNull(store.get(task, "t1"))
        assertEquals("Buy milk", got.title)
        assertEquals(1_000L, got.createdAt)
        assertEquals(1_000L, got.updatedAt)
        assertEquals(listOf("t1"), store.list(task).map { it.id })
        assertEquals(1, store.pending(100).size)
    }

    @Test
    fun putUnchangedValueAddsNoOutboxEntry() {
        store.put(task, Task(id = "t1", title = "a"))
        val first = store.pending(100).single()
        store.markSynced(listOf(first))
        wall += 10
        store.put(task, store.get(task, "t1")!!)
        store.put(task, store.get(task, "t1")!!.copy(updatedAt = 999_999))
        assertTrue(store.pending(100).isEmpty())
        assertEquals(1_000L, store.get(task, "t1")!!.updatedAt)
    }

    @Test
    fun putGivesOnlyChangedFieldsNewClocks() {
        store.put(task, Task(id = "t1", title = "a", priority = Priority.LOW))
        val before = store.rawRecord("task", "t1")!!
        wall += 50
        store.put(task, store.get(task, "t1")!!.copy(title = "b"))
        val after = store.rawRecord("task", "t1")!!
        assertEquals(before.clocks["priority"], after.clocks["priority"])
        assertTrue(Hlc.parse(after.clocks.getValue("title"))!! > Hlc.parse(before.clocks.getValue("title"))!!)
        assertEquals(after.clocks["title"], after.clocks["updatedAt"])
        assertEquals(1_050L, store.get(task, "t1")!!.updatedAt)
    }

    @Test
    fun deleteIsTombstoneHiddenFromList() {
        store.put(task, Task(id = "t1", title = "a"))
        store.delete(task, "t1")
        assertTrue(store.list(task).isEmpty())
        assertTrue(store.get(task, "t1")!!.deleted)
        assertTrue(store.rawRecord("task", "t1")!!.isDeleted)
        store.delete(task, "missing")
        assertNull(store.rawRecord("task", "missing"))
    }

    @Test
    fun editDuringSyncStaysPending() {
        store.put(task, Task(id = "t1", title = "a"))
        val inFlight = store.pending(100)
        wall += 1
        store.put(task, store.get(task, "t1")!!.copy(title = "edited while syncing"))
        store.markSynced(inFlight)
        val still = store.pending(100).single()
        assertEquals(JsonPrimitive("edited while syncing"), still.record.fields["title"])
        store.markSynced(listOf(still))
        assertTrue(store.pending(100).isEmpty())
    }

    @Test
    fun applyRemoteMergesWithoutMarkingPending() {
        store.put(task, Task(id = "t1", title = "local", priority = Priority.HIGH))
        store.markSynced(store.pending(100))
        val local = store.rawRecord("task", "t1")!!
        val newer = Hlc(5_000, 0, "remote").encode()
        store.applyRemote(
            listOf(SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("remote")), mapOf("title" to newer))),
        )
        val got = store.get(task, "t1")!!
        assertEquals("remote", got.title)
        assertEquals(Priority.HIGH, got.priority)
        assertEquals(local.clocks["priority"], store.rawRecord("task", "t1")!!.clocks["priority"])
        assertTrue(store.pending(100).isEmpty())
    }

    @Test
    fun applyRemoteAdvancesClockSoNextLocalEditWins() {
        store.put(task, Task(id = "t1", title = "a"))
        val hourAhead = Hlc(wall + 3_600_000, 0, "zzzz").encode()
        store.applyRemote(
            listOf(SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("remote")), mapOf("title" to hourAhead))),
        )
        store.put(task, store.get(task, "t1")!!.copy(title = "mine"))
        assertEquals("mine", store.get(task, "t1")!!.title)
        assertTrue(Hlc.parse(store.rawRecord("task", "t1")!!.clocks.getValue("title"))!! > Hlc.parse(hourAhead)!!)
    }

    @Test
    fun unknownEntityAndUndecodableRecordAreKept() {
        val clock = Hlc(1, 0, "x").encode()
        store.put(task, Task(id = "ok", title = "fine"))
        store.applyRemote(
            listOf(
                SyncRecord("future", "f1", mapOf("id" to JsonPrimitive("f1")), mapOf("id" to clock)),
                SyncRecord(
                    "task", "bad",
                    mapOf("id" to JsonPrimitive("bad"), "priority" to JsonPrimitive("high")),
                    mapOf("id" to clock, "priority" to clock),
                ),
            ),
        )
        assertEquals(listOf("ok"), store.list(task).map { it.id })
        assertNull(store.get(task, "bad"))
        assertNotNull(store.rawRecord("future", "f1"))
        assertNotNull(store.rawRecord("task", "bad"))
    }

    @Test
    fun putKeepsFieldsThisClientDoesNotKnow() {
        val clock = Hlc(1, 0, "x").encode()
        store.applyRemote(
            listOf(
                SyncRecord(
                    "task", "t1",
                    mapOf("id" to JsonPrimitive("t1"), "futureField" to JsonPrimitive(42)),
                    mapOf("id" to clock, "futureField" to clock),
                ),
            ),
        )
        store.put(task, store.get(task, "t1")!!.copy(title = "edited"))
        assertEquals(JsonPrimitive(42), store.rawRecord("task", "t1")!!.fields["futureField"])
    }

    @Test
    fun markAllPendingQueuesEveryRecord() {
        store.put(task, Task(id = "a"))
        store.put(task, Task(id = "b"))
        store.markSynced(store.pending(100))
        store.markAllPending()
        assertEquals(setOf("a", "b"), store.pending(100).map { it.record.id }.toSet())
    }

    @Test
    fun checkpointAndServerIdPersistInMeta() {
        assertEquals(0L, store.checkpoint)
        assertNull(store.serverId)
        store.checkpoint = 42
        store.serverId = "srv"
        assertEquals(42L, store.checkpoint)
        assertEquals("srv", store.serverId)
    }

    @Test
    fun observeEmitsCurrentList() = runTest {
        store.put(task, Task(id = "t1", title = "a"))
        assertEquals(listOf("t1"), store.observe(task).first().map { it.id })
    }

    @Test
    fun nodeIdsDifferBetweenDatabases() {
        val other = ZenStore(createSqlDriver(null), wall = { wall })
        assertNotEquals(store.nodeId, other.nodeId)
    }

    @Test
    fun putRejectsServerOwnedType() {
        assertFailsWith<IllegalArgumentException> {
            store.put(EntityTypes.calendarEvent, CalendarEvent(id = "e1"))
        }
    }

    @Test
    fun markAllPendingSkipsServerOwnedEntities() {
        val clock = Hlc(1, 0, "srv").encode()
        store.applyRemote(listOf(SyncRecord("calendarEvent", "e1", mapOf("id" to JsonPrimitive("e1")), mapOf("id" to clock))))
        store.put(task, Task(id = "t1"))
        store.markSynced(store.pending(100))
        store.markAllPending()
        assertEquals(listOf("t1"), store.pending(100).map { it.record.id })
    }

    @Test
    fun resetForNewServerQueuesEverythingAndRestartsPull() {
        store.put(task, Task(id = "a"))
        store.markSynced(store.pending(100))
        store.checkpoint = 30
        store.serverId = "old"
        store.resetForNewServer("new")
        assertEquals("new", store.serverId)
        assertEquals(0L, store.checkpoint)
        assertEquals(listOf("a"), store.pending(100).map { it.record.id })
    }

    @Test
    fun staleCopyWithBaseKeepsRemoteEdit() {
        store.put(task, Task(id = "t1", title = "old", priority = Priority.NONE))
        val uiCopy = store.get(task, "t1")!!
        store.applyRemote(
            listOf(SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("remote")), mapOf("title" to Hlc(9_000, 0, "zz").encode()))),
        )
        wall += 10_000
        store.put(task, uiCopy.copy(priority = Priority.HIGH), base = uiCopy)
        val got = store.get(task, "t1")!!
        assertEquals("remote", got.title)
        assertEquals(Priority.HIGH, got.priority)
    }

    @Test
    fun updateTransformsTheCurrentValue() {
        store.put(task, Task(id = "t1", title = "a"))
        store.applyRemote(
            listOf(SyncRecord("task", "t1", mapOf("title" to JsonPrimitive("remote")), mapOf("title" to Hlc(9_000, 0, "zz").encode()))),
        )
        wall += 10_000
        val updated = store.update(task, "t1") { it.copy(priority = Priority.LOW) }
        assertEquals("remote", updated!!.title)
        assertEquals(Priority.LOW, store.get(task, "t1")!!.priority)
        assertNull(store.update(task, "missing") { it })
    }

    @Test
    fun parkedChangeLeavesPendingUntilEditedAgain() {
        store.put(task, Task(id = "t1", title = "a"))
        val out = store.pending(100).single()
        store.park(out, "bad_request")
        assertTrue(store.pending(100).isEmpty())
        assertEquals(listOf("t1" to "bad_request"), store.parked().map { it.first.id to it.second })
        wall += 1
        store.put(task, store.get(task, "t1")!!.copy(title = "b"))
        assertEquals(listOf("t1"), store.pending(100).map { it.record.id })
        assertTrue(store.parked().isEmpty())
    }

    @Test
    fun restampMovesFarFutureClocksToNow() {
        val future = Hlc(wall + 48 * 3_600_000L, 0, "skewed").encode()
        store.applyRemote(listOf(SyncRecord("task", "t1", mapOf("id" to JsonPrimitive("t1"), "title" to JsonPrimitive("x")), mapOf("id" to future, "title" to future))))
        store.restampFuture("task", "t1")
        val rec = store.rawRecord("task", "t1")!!
        assertTrue(rec.clocks.values.all { Hlc.parse(it)!!.millis <= wall + 3_600_000L }, "${rec.clocks}")
        assertEquals(JsonPrimitive("x"), rec.fields["title"])
        assertEquals(listOf("t1"), store.pending(100).map { it.record.id })
    }
}
