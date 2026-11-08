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
import com.vuhpquang.zentask.calendar.CalendarDay
import com.vuhpquang.zentask.calendar.CalendarQueries
import com.vuhpquang.zentask.lists.FilterRule
import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.model.ZenJson
import kotlinx.datetime.toLocalDateTime
import com.vuhpquang.zentask.tasks.SmartList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.vuhpquang.zentask.lists.ListActions
import com.vuhpquang.zentask.lists.SortOrder
import com.vuhpquang.zentask.lists.ViewOptions
import com.vuhpquang.zentask.model.Filter
import com.vuhpquang.zentask.model.Preference
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.tasks.PlannedReminder
import com.vuhpquang.zentask.tasks.QuickAdd
import com.vuhpquang.zentask.tasks.ReminderPlanner
import com.vuhpquang.zentask.tasks.QuickAddParser
import com.vuhpquang.zentask.tasks.Recurrence
import com.vuhpquang.zentask.tasks.TagActions
import com.vuhpquang.zentask.tasks.TaskActions
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.TaskIndex
import com.vuhpquang.zentask.tasks.TaskSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.vuhpquang.zentask.model.ProjectGroup
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant
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

    /** Task operations (complete, trash, edit, checklist…); every edit is offline-first and synced. */
    val actions: TaskActions = TaskActions(store)
    val tagActions: TagActions = TagActions(store)
    val listActions: ListActions = ListActions(store)

    /** Any record changed (locally or from sync): reload the index. */
    val recordChanges: Flow<Unit> = store.recordChanges()

    // ---- Tasks ----

    /** Reads the task data into a [TaskIndex] off the calling thread. */
    @Throws(Exception::class)
    suspend fun loadIndex(): TaskIndex = io { TaskIndex.load(store) }

    fun sections(index: TaskIndex, filter: TaskFilter, options: ViewOptions): List<TaskSection> = index.sections(filter, now(), zone(), options)

    /** The saved sort/group choice for a list (empty fields = the list's default). */
    fun viewOptions(filter: TaskFilter): ViewOptions =
        store.get(EntityTypes.preference, ViewOptions.key(filterKey(filter)))?.takeIf { !it.deleted }?.let { ViewOptions.parse(it.value) } ?: ViewOptions()

    fun setViewOptions(filter: TaskFilter, options: ViewOptions) {
        val id = ViewOptions.key(filterKey(filter))
        val existing = store.get(EntityTypes.preference, id)
        val value = options.encode()
        if (existing == null) {
            store.put(EntityTypes.preference, Preference(id = id, key = id, value = value))
        } else {
            store.update(EntityTypes.preference, id) { it.copy(value = value, deleted = false) }
        }
    }

    /** Creates (id null) or updates a custom smart list. */
    fun saveFilter(id: String?, name: String, rule: FilterRule, icon: String?): Filter {
        val clean = name.trim().ifEmpty { "Smart list" }
        return if (id == null) {
            val last = store.list(EntityTypes.filter).maxOfOrNull { it.sortOrder }
            Filter(id = kotlin.uuid.Uuid.random().toString(), name = clean, rule = rule.encode(), sortOrder = SortOrder.between(last, null)!!, icon = icon)
                .also { store.put(EntityTypes.filter, it) }
        } else {
            store.update(EntityTypes.filter, id) { it.copy(name = clean, rule = rule.encode(), icon = icon) }!!
        }
    }

    fun deleteFilter(id: String) = store.delete(EntityTypes.filter, id)

    /** How each built-in smart list shows in the sidebar: `show`, `nonempty` (show if not empty) or `hide`. */
    fun smartListMode(list: SmartList): String =
        smartListModes()[list.name] ?: if (list == SmartList.TOMORROW || list == SmartList.WONT_DO) "nonempty" else "show"

    fun setSmartListMode(list: SmartList, mode: String) {
        val next = smartListModes() + (list.name to mode)
        val value = ZenJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), next)
        if (store.get(EntityTypes.preference, SMART_LISTS) == null) {
            store.put(EntityTypes.preference, Preference(id = SMART_LISTS, key = SMART_LISTS, value = value))
        } else {
            store.update(EntityTypes.preference, SMART_LISTS) { it.copy(value = value, deleted = false) }
        }
    }

    private fun smartListModes(): Map<String, String> =
        store.get(EntityTypes.preference, SMART_LISTS)?.takeIf { !it.deleted }?.value
            ?.let { runCatching { ZenJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() } ?: emptyMap()

    fun filterRule(filter: Filter): FilterRule = FilterRule.parse(filter.rule) ?: FilterRule()

    /** Sidebar badges keyed `smart:TODAY`, `project:<id>`, `tag:<name>` (Swift can't key a dictionary by the sealed interface). */
    fun sidebarCounts(index: TaskIndex): Map<String, Int> = index.counts(now(), zone()).mapKeys { (f, _) -> filterKey(f) }

    fun filterKey(filter: TaskFilter): String = when (filter) {
        is TaskFilter.Smart -> "smart:${filter.list.name}"
        is TaskFilter.InProject -> "project:${filter.id}"
        is TaskFilter.WithTag -> "tag:${filter.name.lowercase()}"
        is TaskFilter.Custom -> "filter:${filter.id}"
    }

    /** [ignored]: start offsets of tokens the user dismissed (kept as text). */
    fun parseQuickAdd(text: String, projects: List<Project>, ignored: Set<Int>): QuickAdd =
        QuickAddParser.parse(text, now(), zone(), projects, ignored)

    /** Creates a task from quick-add text in the list being viewed. */
    fun quickAdd(text: String, filter: TaskFilter, projects: List<Project>, ignored: Set<Int>): Task? {
        val q = parseQuickAdd(text, projects, ignored)
        if (q.title.isBlank()) return null
        return actions.create(q.title, filter, q.projectId, q.priority, q.tags, q.due, q.allDay)
    }

    /** Start of the task's day in the current zone, as epoch millis (all-day dates read in the task's own zone). */
    fun taskDayMillis(index: TaskIndex, task: Task): Long? =
        index.day(task, zone())?.atStartOfDayIn(zone())?.toEpochMilliseconds()

    fun projectGroups(): List<ProjectGroup> = store.list(EntityTypes.projectGroup).sortedBy { it.sortOrder }

    fun describeRepeat(rule: String): String = Recurrence.describe(rule)

    // ---- Calendar ----

    /** [count] day columns starting at the local day of [firstDayMillis] (events of visible calendars + dated tasks). */
    fun calendarDays(firstDayMillis: Long, count: Int): List<CalendarDay> {
        val z = zone()
        val first = Instant.fromEpochMilliseconds(firstDayMillis).toLocalDateTime(z).date
        return CalendarQueries.days(
            store.list(EntityTypes.calendarEvent), store.list(EntityTypes.calendarSource), store.list(EntityTypes.calendarAccount),
            store.list(EntityTypes.task), first, count, z, hiddenCalendars(),
        )
    }

    data class CalendarInfo(val source: CalendarSource, val account: CalendarAccount?, val hidden: Boolean)

    /** Calendars with their account (for Settings → Calendars). */
    fun calendars(): List<CalendarInfo> {
        val accounts = store.list(EntityTypes.calendarAccount).associateBy { it.id }
        val hidden = hiddenCalendars()
        return store.list(EntityTypes.calendarSource).filter { it.accountId in accounts }.sortedWith(compareBy({ accounts[it.accountId]?.email }, { it.name }))
            .map { CalendarInfo(it, accounts[it.accountId], it.id in hidden) }
    }

    fun calendarAccounts(): List<CalendarAccount> = store.list(EntityTypes.calendarAccount)

    fun hiddenCalendars(): Set<String> =
        store.get(EntityTypes.preference, HIDDEN_CALENDARS)?.takeIf { !it.deleted }?.value
            ?.let { runCatching { ZenJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }?.toSet() ?: emptySet()

    fun setCalendarHidden(sourceId: String, hidden: Boolean) {
        val next = (if (hidden) hiddenCalendars() + sourceId else hiddenCalendars() - sourceId).sorted()
        val value = ZenJson.encodeToString(ListSerializer(String.serializer()), next)
        if (store.get(EntityTypes.preference, HIDDEN_CALENDARS) == null) {
            store.put(EntityTypes.preference, Preference(id = HIDDEN_CALENDARS, key = HIDDEN_CALENDARS, value = value))
        } else {
            store.update(EntityTypes.preference, HIDDEN_CALENDARS) { it.copy(value = value, deleted = false) }
        }
    }

    @Throws(Exception::class)
    suspend fun googleConnectUrl(): String = api.calendarConnectUrl()

    @Throws(Exception::class)
    suspend fun addIcal(name: String, url: String) {
        api.addIcal(name, url)
        scheduler.requestNow()
    }

    @Throws(Exception::class)
    suspend fun removeCalendarAccount(id: String) {
        api.removeCalendarAccount(id)
        scheduler.requestNow()
    }

    @Throws(Exception::class)
    suspend fun refreshCalendars() {
        api.refreshCalendars()
        scheduler.requestNow()
    }

    /** Reminders to schedule as local notifications (next 14 days, soonest first). */
    fun plannedReminders(index: TaskIndex): List<PlannedReminder> = ReminderPlanner.upcoming(index.allTasks, now(), zone())

    private fun now(): Instant = Clock.System.now()
    private fun zone(): TimeZone = TimeZone.currentSystemDefault()

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
    suspend fun importCsv(text: String): SyncPlan = io { ticktick.importCsv(TickTickCsv.parse(text)) }.also { scheduler.requestNow() }

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
        private const val HIDDEN_CALENDARS = "calendar.hidden"
        private const val SMART_LISTS = "sidebar.smartLists"

        /** Swift-friendly constructor: default server and the platform HTTP client. */
        fun open(dbPath: String?): ZenTaskCore = ZenTaskCore(dbPath)
    }
}
