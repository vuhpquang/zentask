package com.vuhpquang.zentask.android

import android.content.Context
import android.os.Build
import com.vuhpquang.zentask.ZenTaskCore
import com.vuhpquang.zentask.sync.SyncStatus
import com.vuhpquang.zentask.tasks.TaskIndex
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
class ZenApp(context: Context) {
    val core: ZenTaskCore = ZenTaskCore.open("zentask.db")
    private val prefs = context.getSharedPreferences("zentask", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    @OptIn(FlowPreview::class)
    fun start() {
        // The token lives in the app's private preferences (app-only storage on Android).
        prefs.getString("server.token", null)?.let { token ->
            core.token = token
            _signedIn.value = true
            core.startSync()
        }
        scope.launch {
            core.recordChanges.onStart { emit(Unit) }.debounce(80).collect {
                _index.value = core.loadIndex()
                _revision.value += 1
            }
        }
    }

    suspend fun signIn(password: String): String? = try {
        val token = core.login(password, deviceName())
        prefs.edit().putString("server.token", token).apply()
        _signedIn.value = true
        core.startSync()
        null
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

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme.mode", mode).apply()
        _themeMode.value = mode
    }

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" ")
}
