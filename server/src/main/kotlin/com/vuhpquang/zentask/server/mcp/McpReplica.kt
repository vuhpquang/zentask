package com.vuhpquang.zentask.server.mcp

import com.vuhpquang.zentask.habits.CountdownActions
import com.vuhpquang.zentask.habits.HabitActions
import com.vuhpquang.zentask.lists.ListActions
import com.vuhpquang.zentask.server.store.ServerDb
import com.vuhpquang.zentask.server.sync.rejection
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import com.vuhpquang.zentask.tasks.TagActions
import com.vuhpquang.zentask.tasks.TaskActions
import com.vuhpquang.zentask.tasks.TaskIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.nio.file.Path

/**
 * The MCP server's own copy of the data: a regular client store (`ZenStore`, its own HLC node) kept in
 * step with [ServerDb] in-process. Each [transact] pulls everything newer from the server database,
 * runs the shared task rules on the replica, then pushes the replica's outbox through `ServerDb.apply`
 * (per-field merge) — so AI edits follow exactly the same rules as edits made on a device.
 */
class McpReplica(
    private val db: ServerDb,
    file: Path,
    private val wall: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    class Context(
        val store: ZenStore,
        val actions: TaskActions,
        val tags: TagActions,
        val lists: ListActions,
        val habits: HabitActions,
        val countdowns: CountdownActions,
    ) {
        val index: TaskIndex by lazy { TaskIndex.load(store) }
    }

    /** Zone used for "today" and plain dates; tools can override it per call. */
    @Volatile
    var zone: TimeZone = TimeZone.of("Asia/Ho_Chi_Minh")

    private val store = ZenStore(createSqlDriver(file.toString()), wall)
    private val actions = TaskActions(store, wall) { zone }
    private val tagActions = TagActions(store)
    private val listActions = ListActions(store, wall)
    private val habitActions = HabitActions(store, wall)
    private val countdownActions = CountdownActions(store, wall)
    private val mutex = Mutex()

    suspend fun <T> transact(block: (Context) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            pull()
            val result = block(Context(store, actions, tagActions, listActions, habitActions, countdownActions))
            push()
            pull() // advance the checkpoint past our own writes (so a restored server is noticed)
            result
        }
    }

    private fun pull() {
        // A checkpoint the server never issued: its database went back in time (restored backup).
        if (store.checkpoint > db.currentVersion()) db.rotateServerId()
        val known = store.serverId
        if (known != null && known != db.serverId) store.resetForNewServer(db.serverId)
        store.serverId = db.serverId
        while (true) {
            val (rows, hasMore) = db.changesSince(store.checkpoint, PAGE)
            if (rows.isNotEmpty()) {
                store.applyRemote(rows.map { it.second })
                store.checkpoint = rows.last().first
            }
            if (!hasMore) break
        }
    }

    /** Pushes the outbox with the same checks as `/v1/sync`; a refused change is parked, never applied. */
    private fun push() {
        while (true) {
            val out = store.pending(PAGE)
            if (out.isEmpty()) break
            val now = wall()
            val (bad, good) = out.partition { rejection(it.record, now) != null }
            bad.forEach { store.park(it, rejection(it.record, now)!!) }
            db.apply(good.map { it.record })
            store.markSynced(good)
        }
    }

    /** True when the last action left unpushed changes (used to audit partial writes). */
    fun hasPending(): Boolean = store.pending(1).isNotEmpty()

    override fun close() = store.close()

    private companion object {
        const val PAGE = 500
    }
}
