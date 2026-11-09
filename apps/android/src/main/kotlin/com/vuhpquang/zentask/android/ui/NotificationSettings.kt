package com.vuhpquang.zentask.android.ui

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.notify.NotificationPrefs
import com.vuhpquang.zentask.tasks.ReminderPresets

/** Settings → Notifications (synced `NotificationPrefs`, the same choices as on the Mac). */
@Composable
fun NotificationsSection(app: ZenApp) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(app.core.notificationPrefs()) }
    fun save(p: NotificationPrefs) {
        prefs = p
        app.core.setNotificationPrefs(p)
    }
    Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        Text("Notifications are turned off for ZenTask.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Turn on notifications") }
    }
    if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        Text("Reminders may be a few minutes late: Android hasn't allowed exact alarms for ZenTask.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SwitchRow("Task reminders", prefs.tasks) { save(prefs.copy(tasks = it)) }
    SwitchRow("Habit reminders", prefs.habits) { save(prefs.copy(habits = it)) }
    SwitchRow("Countdowns", prefs.countdowns) { save(prefs.copy(countdowns = it)) }
    SwitchRow("Daily summary", prefs.summary) { save(prefs.copy(summary = it)) }
    if (prefs.summary) TimeRow("Summary at", prefs.summaryMinutes) { save(prefs.copy(summaryMinutes = it)) }
    TimeRow("All-day task reminders at", prefs.allDayMinutes) { save(prefs.copy(allDayMinutes = it)) }
    TimeRow("Countdown reminders at", prefs.countdownMinutes) { save(prefs.copy(countdownMinutes = it)) }
    Text("Remind about countdowns", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for ((days, label) in listOf(0 to "On the day", 1 to "1 day before", 3 to "3 days before", 7 to "1 week before")) {
            FilterChip(selected = prefs.countdownDaysBefore == days, onClick = { save(prefs.copy(countdownDaysBefore = days)) }, label = { Text(label) })
        }
    }
    Text("Default reminder for new tasks", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    PresetRow("With a time", prefs.defaultTimed, ReminderPresets.timed) { save(prefs.copy(defaultTimed = it)) }
    PresetRow("All-day", prefs.defaultAllDay, ReminderPresets.allDay) { save(prefs.copy(defaultAllDay = it)) }
    SwitchRow("Play a sound", prefs.sound) { save(prefs.copy(sound = it)) }
}

@Composable
private fun SwitchRow(title: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = change)
    }
}

@Composable
private fun TimeRow(title: String, minutes: Int, change: (Int) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        TextButton(onClick = {
            TimePickerDialog(context, { _, h, m -> change(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
        }) { Text("%02d:%02d".format(minutes / 60, minutes % 60)) }
    }
}

@Composable
private fun PresetRow(title: String, value: String, presets: List<Pair<String, String>>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(presets.firstOrNull { it.first == value }?.second ?: "None") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("None") }, onClick = { change(""); open = false })
                for ((trigger, label) in presets) DropdownMenuItem(text = { Text(label) }, onClick = { change(trigger); open = false })
            }
        }
    }
}
