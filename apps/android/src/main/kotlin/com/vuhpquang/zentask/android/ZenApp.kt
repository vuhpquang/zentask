package com.vuhpquang.zentask.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.vuhpquang.zentask.ZenTaskCore
import com.vuhpquang.zentask.sync.SyncStatus
import com.vuhpquang.zentask.tasks.TaskIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The app's state, shared by every screen: the shared core (database `zentask.db`), sign-in, sync status,
 * and the task index reloaded (off the main thread) whenever any record changes.
 */
class ZenApp(private val context: Context) {
    val core: ZenTaskCore = ZenTaskCore.open("zentask.db")
    private val reminders = Reminders(context)
    val updater = AppUpdater(context)

    /** A task to open, set when a reminder notification is tapped. */
    val openTask = MutableStateFlow<String?>(null)
    /** Something asked for Settings → Places. */
    val showPlaces = MutableStateFlow(false)
    /** A widget's "+" asked for the quick-add sheet (TasksScreen opens it and clears this). */
    val quickAdd = MutableStateFlow(false)
    /** Set to show the Focus area (from a task's "Start focus"). */
    val showFocus = MutableStateFlow(false)
    private val prefs = context.getSharedPreferences("zentask", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unexpected error", e) })
    /** Attachment bytes (cache, upload, download). */
    val files by lazy { AttachmentFiles(context, core, scope) }

    private val _index = MutableStateFlow<TaskIndex?>(null)
    val index: StateFlow<TaskIndex?> = _index.asStateFlow()

    /** Bumped on every data change, for screens that read other records (habits, countdowns, calendar, focus). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val syncStatus: StateFlow<SyncStatus> get() = core.syncStatus

    /** "system", "light" or "dark". */
    private val _themeMode = MutableStateFlow(prefs.getString("theme.mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    // ---- Focus timer: kept here (and in preferences) so it survives leaving the tab and a restart ----

    /** Start of the running session (ms), 0 when stopped. */
    private val _focusStartedAt = MutableStateFlow(prefs.getLong("focus.startedAt", 0L))
    val focusStartedAt: StateFlow<Long> = _focusStartedAt.asStateFlow()
    private val _focusStopwatch = MutableStateFlow(prefs.getBoolean("focus.stopwatch", false))
    /** The task the session is for (saved with the record), or null. */
    private val _focusTaskId = MutableStateFlow(prefs.getString("focus.taskId", null))
    val focusTaskId: StateFlow<String?> = _focusTaskId.asStateFlow()

    fun setFocusTask(id: String?) {
        _focusTaskId.value = id
        prefs.edit().putString("focus.taskId", id).apply()
    }

    /** "Start focus" on a task: picks it, starts a session if none is running and shows the Focus area. */
    fun focusOn(taskId: String) {
        setFocusTask(taskId)
        if (_focusStartedAt.value == 0L) startFocus()
        showFocus.value = true
    }
    val focusStopwatch: StateFlow<Boolean> = _focusStopwatch.asStateFlow()
    val pomoMillis: Long = com.vuhpquang.zentask.focus.PomodoroPlan().focusMinutes * 60_000L

    fun setFocusStopwatch(on: Boolean) {
        if (_focusStartedAt.value > 0) return
        _focusStopwatch.value = on
        prefs.edit().putBoolean("focus.stopwatch", on).apply()
    }

    fun startFocus() {
        val now = System.currentTimeMillis()
        setFocusStarted(now)
        watchPomo()
    }

    /** Stops the session; a stopwatch session is saved, an unfinished pomo is not (like the Mac app). */
    fun stopFocus() {
        val start = _focusStartedAt.value
        if (start == 0L) return
        if (_focusStopwatch.value) {
            core.focusActions.recordOrNull(start, System.currentTimeMillis(), com.vuhpquang.zentask.model.FocusRecord.KIND_STOPWATCH, _focusTaskId.value, "")
        }
        reminders.cancelFocusEnd()
        setFocusStarted(0L)
    }

    /** Saves a pomo whose time is up (from the screen's timer, the end alarm, or at start). True if it did. */
    fun finishPomoIfDue(): Boolean {
        val start = _focusStartedAt.value
        if (start == 0L || _focusStopwatch.value || System.currentTimeMillis() < start + pomoMillis) return false
        core.focusActions.record(start, start + pomoMillis, com.vuhpquang.zentask.model.FocusRecord.KIND_POMO, _focusTaskId.value, "")
        setFocusStarted(0L)
        return true
    }

    private fun setFocusStarted(at: Long) {
        _focusStartedAt.value = at
        prefs.edit().putLong("focus.startedAt", at).apply()
    }

    private var pomoWatcher: kotlinx.coroutines.Job? = null

    private fun watchPomo() {
        pomoWatcher?.cancel()
        val start = _focusStartedAt.value
        if (start == 0L || _focusStopwatch.value) return
        reminders.armFocusEnd(start + pomoMillis) // again at start: a force-stop drops alarms
        pomoWatcher = scope.launch {
            kotlinx.coroutines.delay((start + pomoMillis - System.currentTimeMillis()).coerceAtLeast(0))
            finishPomoIfDue()
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        finishPomoIfDue()
        watchPomo()
        // The token lives in the app's private preferences (app-only storage on Android).
        prefs.getString("server.token", null)?.let { token ->
            core.token = token
            _signedIn.value = true
            core.startSync()
        }
        scope.launch {
            core.recordChanges.onStart { emit(Unit) }.debounce(80).collect {
                // One bad reload must never crash the app (this runs at every start, also from a reminder or boot).
                val index = try {
                    core.loadIndex()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't load tasks", e)
                    return@collect
                }
                _index.value = index
                _revision.value += 1
                launch(Dispatchers.Default) {
                    try {
                        reminders.sync(core.plannedReminders(index))
                        TodayWidget.refresh(context)
                        files.syncSoon()
                        LocationReminders.sync(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Couldn't update reminders or the widget", e)
                    }
                }
            }
        }
    }

    suspend fun signIn(password: String): String? = try {
        val token = core.login(password, deviceName())
        prefs.edit().putString("server.token", token).apply()
        _signedIn.value = true
        core.startSync()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.message?.contains("401") == true) "Wrong password" else "Couldn't reach the ZenTask server (${e.message ?: e::class.simpleName})"
    }

    fun signOut() {
        core.stopSync()
        core.token = null
        prefs.edit().remove("server.token").apply()
        _signedIn.value = false
    }

    fun syncNow() = core.requestSync()

    /** Shows the notification fired by [fire] again at [at]. */
    fun snooze(fire: android.content.Intent, at: Long) = reminders.snooze(fire, at)

    /** Reschedules every reminder now (after a reboot, when the app may be stopped again soon). */
    suspend fun rescheduleReminders(force: Boolean) {
        val index = core.loadIndex()
        reminders.sync(core.plannedReminders(index), force)
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme.mode", mode).apply()
        _themeMode.value = mode
    }

    private companion object {
        const val TAG = "ZenTask"
    }

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ")
}
