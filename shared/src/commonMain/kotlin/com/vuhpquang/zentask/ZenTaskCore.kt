package com.vuhpquang.zentask

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.model.draft
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import com.vuhpquang.zentask.sync.SyncApi
import com.vuhpquang.zentask.sync.SyncEngine
import com.vuhpquang.zentask.sync.SyncScheduler
import com.vuhpquang.zentask.sync.SyncStatus
import com.vuhpquang.zentask.ticktick.SyncPlan
import com.vuhpquang.zentask.ticktick.TickTickApi
import com.vuhpquang.zentask.ticktick.TickTickCsv
import com.vuhpquang.zentask.ticktick.TickTickSnapshot
import com.vuhpquang.zentask.ticktick.TickTickSync
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** What the main window shows until the task UI (P3) exists. */
data class Counts(val lists: Int, val openTasks: Int, val completedTasks: Int, val trashedTasks: Int)

/**
 * Entry point for the native apps: the local database at [dbPath] (null = in-memory), sync with the
 * ZenTask server, and the TickTick import. Generic Kotlin APIs export poorly to Swift, so typed helpers
 * live here and grow phase by phase. Functions that can fail are `@Throws` so Swift gets an error
 * instead of a crash. In Swift, Kotlin's `Task` is `ZenTaskShared.Task` (Swift has its own `Task`).
 */
class ZenTaskCore(
    dbPath: String?,
    serverUrl: String = DEFAULT_SERVER,
    private val http: HttpClient = defaultHttpClient(),
) {
    val store: ZenStore = ZenStore(createSqlDriver(dbPath))

    /** Device token for the ZenTask server; the app keeps it in the Keychain and sets it here. */
    var token: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val api = SyncApi(http, serverUrl, token = { token })
    private val engine = SyncEngine(store, api)
    private val scheduler = SyncScheduler(scope, engine, store.changes)
    private val ticktick = TickTickSync(store)

    val syncStatus: StateFlow<SyncStatus> = engine.status

    // ---- ZenTask server ----

    /** Signs this device in; returns the device token (also set on [token]). */
    @Throws(Exception::class)
    suspend fun login(password: String, deviceName: String): String {
        val response = api.login(password, deviceName)
        token = response.token
        return response.token
    }

    /** Starts background sync (debounced on changes, every 5 minutes) and syncs once now. */
    fun startSync() {
        scheduler.start()
        scheduler.requestNow()
    }

    fun stopSync() = scheduler.stop()

    fun requestSync() = scheduler.requestNow()

    // ---- TickTick ----

    /** Downloads everything from TickTick with the session [cookie] (throws TickTickException). */
    @Throws(Exception::class)
    suspend fun ticktickSnapshot(cookie: String): TickTickSnapshot = TickTickApi(http, cookie = { cookie }).snapshot()

    @Throws(Exception::class)
    suspend fun ticktickPreview(snapshot: TickTickSnapshot): SyncPlan = io { ticktick.preview(snapshot) }

    /** Applies a TickTick sync; throws MassTrashException unless [confirmMassTrash] when the guard trips. */
    @Throws(Exception::class)
    suspend fun ticktickApply(snapshot: TickTickSnapshot, confirmMassTrash: Boolean): SyncPlan =
        io { ticktick.apply(snapshot, confirmMassTrash) }.also { scheduler.requestNow() }

    /** Imports a TickTick CSV backup (only before any cookie sync). */
    @Throws(Exception::class)
    fun importCsv(text: String): SyncPlan = ticktick.importCsv(TickTickCsv.parse(text)).also { scheduler.requestNow() }

    val ticktickLastSyncAt: Long? get() = ticktick.lastSyncAt
    val ticktickHasApiData: Boolean get() = ticktick.hasApiData

    // ---- Data ----

    fun counts(): Counts {
        val tasks = store.list(EntityTypes.task)
        val live = tasks.filter { it.trashedAt == null }
        return Counts(
            lists = store.list(EntityTypes.project).count { it.trashedAt == null },
            openTasks = live.count { it.status == TaskStatus.OPEN },
            completedTasks = live.count { it.status == TaskStatus.COMPLETED || it.status == 1 },
            trashedTasks = tasks.size - live.size,
        )
    }

    fun draftTask(title: String, now: Long): Task = Task.draft(title, now = now)

    fun putTask(task: Task) = store.put(EntityTypes.task, task)

    fun task(id: String): Task? = store.get(EntityTypes.task, id)

    fun tasks(): List<Task> = store.list(EntityTypes.task)

    fun close() {
        scope.cancel()
        store.close()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    companion object {
        const val DEFAULT_SERVER = "https://zentask-api.vuhpquang.com"

        /** Swift-friendly constructor: default server and the platform HTTP client. */
        fun open(dbPath: String?): ZenTaskCore = ZenTaskCore(dbPath)
    }
}
