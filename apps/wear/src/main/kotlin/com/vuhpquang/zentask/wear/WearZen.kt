package com.vuhpquang.zentask.wear

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.vuhpquang.zentask.ZenTaskCore
import com.vuhpquang.zentask.habits.HabitRow
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.store.AndroidPlatform
import com.vuhpquang.zentask.tasks.SmartList
import com.vuhpquang.zentask.tasks.TaskFilter
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
import kotlinx.coroutines.withContext

class WearApplication : Application() {
    lateinit var zen: WearZen
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.context = this
        zen = WearZen(this).also { it.start() }
    }
}

/** What the watch shows: today's open tasks and today's habits. */
data class WearToday(val tasks: List<Task>, val habits: List<HabitRow>)

/**
 * The watch app's state. It is a full ZenTask device of its own: the shared core with its own database,
 * signed in once with the server password, syncing directly (so it works without the phone nearby).
 */
class WearZen(context: Context) {
    val core: ZenTaskCore = ZenTaskCore.open("zentask.db")
    private val prefs = context.getSharedPreferences("zentask", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unexpected error", e) })

    private val _today = MutableStateFlow<WearToday?>(null)
    val today: StateFlow<WearToday?> = _today.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        prefs.getString("server.token", null)?.let { token ->
            core.token = token
            _signedIn.value = true
            core.startSync()
        }
        scope.launch {
            core.recordChanges.onStart { emit(Unit) }.debounce(80).collect {
                try {
                    _today.value = withContext(Dispatchers.Default) { load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't load today", e)
                }
            }
        }
    }

    private suspend fun load(): WearToday {
        val index = core.loadIndex()
        val filter = TaskFilter.Smart(SmartList.TODAY)
        val tasks = core.sections(index, filter, core.viewOptions(filter)).filter { !it.isCompleted }.flatMap { s -> s.rows.map { it.task } }
        val habits = core.habitRows().filter { !it.archived && (it.dueToday || it.doneToday) }
        return WearToday(tasks, habits)
    }

    fun complete(taskId: String) = core.actions.complete(taskId)

    fun checkIn(habitId: String) = core.habitCheckIn(habitId, core.todayStamp())

    suspend fun signIn(password: String): String? = try {
        val token = core.login(password, deviceName())
        prefs.edit().putString("server.token", token).apply()
        _signedIn.value = true
        core.startSync()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.message?.contains("401") == true) "Wrong password" else "Couldn't reach the server"
    }

    fun signOut() {
        core.stopSync()
        core.token = null
        prefs.edit().remove("server.token").apply()
        _signedIn.value = false
    }

    fun syncNow() = core.requestSync()

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ")

    private companion object {
        const val TAG = "ZenTaskWear"
    }
}
