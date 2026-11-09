package com.vuhpquang.zentask.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.vuhpquang.zentask.android.ui.CalendarScreen
import com.vuhpquang.zentask.android.ui.CountdownScreen
import com.vuhpquang.zentask.android.ui.FocusScreen
import com.vuhpquang.zentask.android.ui.HabitsScreen
import com.vuhpquang.zentask.android.ui.SearchScreen
import com.vuhpquang.zentask.android.ui.SettingsScreen
import com.vuhpquang.zentask.android.ui.TaskDetailScreen
import com.vuhpquang.zentask.android.ui.TasksScreen
import com.vuhpquang.zentask.android.ui.ZenTaskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val zen = (application as ZenTaskApplication).zen
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Not on a restore or a launch from Recents: those replay the original intent of an old notification.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handle(intent)
        setContent {
            val mode by zen.themeMode.collectAsState()
            ZenTaskTheme(mode) { ZenTaskRoot(zen) }
        }
    }

    override fun onResume() {
        super.onResume()
        val updater = (application as ZenTaskApplication).zen.updater
        lifecycleScope.launch {
            updater.resumeAfterPermission()
            updater.checkIfDue()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tapped reminder opens its task. */
    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_TASK) ?: return
        intent.removeExtra(EXTRA_OPEN_TASK)
        (application as ZenTaskApplication).zen.openTask.value = id
    }

    companion object {
        const val EXTRA_OPEN_TASK = "openTask"
    }
}

private enum class Area(val title: String, val icon: ImageVector) {
    Tasks("Tasks", Icons.Filled.CheckBox),
    Calendar("Calendar", Icons.Filled.CalendarMonth),
    Habits("Habits", Icons.Filled.TrackChanges),
    Countdown("Countdown", Icons.Filled.HourglassBottom),
    Focus("Focus", Icons.Filled.Timer),
}

@Composable
private fun ZenTaskRoot(zen: ZenApp) {
    var area by rememberSaveable { mutableStateOf(Area.Tasks) }
    var page by rememberSaveable { mutableStateOf<String?>(null) } // "settings", "search", "task:<id>"
    val openTask by zen.openTask.collectAsState()
    androidx.compose.runtime.LaunchedEffect(openTask) {
        openTask?.let { page = "task:$it"; zen.openTask.value = null }
    }
    val showFocus by zen.showFocus.collectAsState()
    androidx.compose.runtime.LaunchedEffect(showFocus) {
        if (showFocus) { page = null; area = Area.Focus; zen.showFocus.value = false }
    }
    when {
        page == "settings" -> { SettingsScreen(zen) { page = null }; return }
        page == "search" -> { SearchScreen(zen, open = { page = "task:$it" }) { page = null }; return }
        page?.startsWith("task:") == true -> { TaskDetailScreen(zen, page!!.removePrefix("task:")) { page = null }; return }
    }
    Scaffold(bottomBar = {
        NavigationBar {
            for (a in Area.entries) {
                NavigationBarItem(selected = area == a, onClick = { area = a }, icon = { Icon(a.icon, null) }, label = { Text(a.title) })
            }
        }
    }) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).fillMaxSize()) {
            when (area) {
                Area.Tasks -> TasksScreen(zen, openSettings = { page = "settings" }, openSearch = { page = "search" })
                Area.Calendar -> CalendarScreen(zen)
                Area.Habits -> HabitsScreen(zen)
                Area.Countdown -> CountdownScreen(zen)
                Area.Focus -> FocusScreen(zen)
            }
        }
    }
}
