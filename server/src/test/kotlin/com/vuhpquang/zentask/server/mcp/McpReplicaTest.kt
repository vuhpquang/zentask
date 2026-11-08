package com.vuhpquang.zentask.server.mcp

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpReplicaTest {
    private var wall = 1_800_000_000_000L
    private val dir = Files.createTempDirectory("mcp-replica")
    private val db = ServerDb(dir.resolve("zentask.db")) { wall }
    private val replica = McpReplica(db, dir.resolve("mcp.db")) { wall }

    /** A device: its own store, synced by hand against the same ServerDb. */
    private val device = ZenStore(createSqlDriver(null), wall = { wall })

    private fun deviceSync() {
        val out = device.pending(1000)
        db.apply(out.map { it.record })
        device.markSynced(out)
        val (rows, _) = db.changesSince(device.checkpoint, 10_000)
        device.applyRemote(rows.map { it.second })
        rows.lastOrNull()?.let { device.checkpoint = it.first }
    }

    @Test
    fun seesDeviceChangesBeforeActing() = runBlocking {
        device.put(EntityTypes.task, Task(id = "t1", title = "From the Mac"))
        deviceSync()
        val title = replica.transact { it.store.get(EntityTypes.task, "t1")?.title }
        assertEquals("From the Mac", title)
    }

    @Test
    fun pushesActionsToServerDb() = runBlocking {
        val id = replica.transact { it.actions.create("From the AI", null).id }
        deviceSync()
        assertEquals("From the AI", device.get(EntityTypes.task, id)?.title)
    }

    @Test
    fun concurrentDeviceEditOfAnotherFieldSurvives() = runBlocking {
        device.put(EntityTypes.task, Task(id = "t1", title = "Plan"))
        deviceSync()
        replica.transact { } // replica has t1
        wall += 1_000
        device.update(EntityTypes.task, "t1") { it.copy(title = "Plan v2") } // not synced yet
        wall += 1_000
        replica.transact { it.actions.setPriority("t1", Priority.HIGH) }
        deviceSync()
        val t = device.get(EntityTypes.task, "t1")!!
        assertEquals("Plan v2", t.title)
        assertEquals(Priority.HIGH, t.priority)
        val onServer = replica.transact { it.store.get(EntityTypes.task, "t1")!! }
        assertEquals("Plan v2", onServer.title)
    }

    @Test
    fun replacedServerDatabaseGetsReplicaDataBack() = runBlocking {
        val id = replica.transact { it.actions.create("Kept", null).id }
        replica.close()
        val fresh = ServerDb(dir.resolve("fresh.db")) { wall }
        McpReplica(fresh, dir.resolve("mcp.db")) { wall }.use { it.transact { } }
        assertTrue(fresh.changesSince(0, 100).first.any { it.second.id == id }, "re-uploaded to the new database")
    }
}
