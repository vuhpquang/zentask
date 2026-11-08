package com.vuhpquang.zentask.android

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vuhpquang.zentask.tasks.PlannedReminder
import com.vuhpquang.zentask.tasks.isOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Task reminders as exact alarms, planned by the shared `ReminderPlanner` (the same reminders the Mac shows).
 * [sync] is called after every index reload: alarms for reminders that are gone are cancelled, the rest are
 * (re)set. The set of scheduled ids is kept in preferences, because AlarmManager can't list its alarms.
 * Alarms don't survive a reboot or an update; [BootReceiver] sets them all again.
 */
class Reminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("zentask.reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /**
     * Arms [planned] and cancels alarms for reminders that are gone. Only alarms whose time changed are set again,
     * unless [force] (after a reboot or an app update, when the system has dropped every alarm).
     */
    @Synchronized
    fun sync(planned: List<PlannedReminder>, force: Boolean = false) {
        val armed: Map<String, Long> = prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty().mapNotNull { e ->
            val cut = e.lastIndexOf('=')
            if (cut <= 0) null else e.substring(0, cut) to (e.substring(cut + 1).toLongOrNull() ?: 0L)
        }.toMap()
        val wanted = planned.associate { it.id to it.fireAt }
        for (id in armed.keys - wanted.keys) alarms.cancel(pending(id, null))
        for (r in planned) {
            if (!force && armed[r.id] == r.fireAt) continue
            val pi = pending(r.id, r)
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAt, pi)
            }
        }
        // The planner looks two weeks ahead: wake twice a day to plan again even if the app isn't opened.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + REPLAN_EVERY, replanIntent())
        prefs.edit().putStringSet(KEY_ARMED, wanted.map { (id, at) -> "$id=$at" }.toSet()).remove(KEY_OLD_IDS).apply()
    }

    private fun pending(id: String, r: PlannedReminder?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(android.net.Uri.parse("zentask-reminder:$id"))
        if (r != null) intent.putExtra(EXTRA_TASK, r.taskId).putExtra(EXTRA_TITLE, r.title)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The end of a pomo: saves it and says so, even when the app isn't open. */
    fun armFocusEnd(at: Long) {
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, focusIntent())
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, focusIntent())
    }

    fun cancelFocusEnd() = alarms.cancel(focusIntent())

    private fun focusIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_FOCUS_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun replanIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_REPLAN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val KEY_ARMED = "armed"
        private const val KEY_OLD_IDS = "scheduled"
        private const val REPLAN_EVERY = 12 * 60 * 60 * 1000L
        const val ACTION_REPLAN = "com.vuhpquang.zentask.REMINDER_REPLAN"
        const val ACTION_FOCUS_END = "com.vuhpquang.zentask.FOCUS_END"
        /** Task notifications use the task id's hash; this one is fixed. */
        const val FOCUS_NOTIFICATION_ID = 0x5A3E
        const val CHANNEL = "reminders"
        const val ACTION_FIRE = "com.vuhpquang.zentask.REMINDER"
        const val ACTION_DONE = "com.vuhpquang.zentask.REMINDER_DONE"
        const val EXTRA_TASK = "taskId"
        const val EXTRA_TITLE = "title"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Stable per task, so a second reminder for the same task replaces the first notification. */
        fun notificationId(taskId: String): Int = taskId.hashCode()
    }
}

/** Shows the reminder, or handles its "Done" button. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val zen = (context.applicationContext as ZenTaskApplication).zen
        if (intent.action == Reminders.ACTION_REPLAN) {
            val result = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try { zen.rescheduleReminders(force = false) } finally { result.finish() }
            }
            return
        }
        if (intent.action == Reminders.ACTION_FOCUS_END) {
            if (zen.finishPomoIfDue()) showFocusDone(context)
            return
        }
        val taskId = intent.getStringExtra(Reminders.EXTRA_TASK) ?: return
        when (intent.action) {
            Reminders.ACTION_DONE -> {
                zen.core.task(taskId)?.takeIf { it.isOpen }?.let { zen.core.actions.complete(taskId) }
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(taskId))
            }
            Reminders.ACTION_FIRE -> {
                val task = zen.core.task(taskId)
                // Planned earlier; skip if the task has since been done, trashed or deleted.
                if (task == null || !task.isOpen || task.deleted || task.trashedAt != null) return
                show(context, taskId, task.title.ifBlank { intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty() })
            }
        }
    }

    private fun showFocusDone(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Focus session done")
            .setContentText("Time for a break")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(Reminders.FOCUS_NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    private fun show(context: Context, taskId: String, title: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, taskId.hashCode(),
            Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("zentask-task:$taskId")).putExtra(MainActivity.EXTRA_OPEN_TASK, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            context, taskId.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_DONE).setData(android.net.Uri.parse("zentask-task:$taskId")).putExtra(Reminders.EXTRA_TASK, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Task reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Done", done)
            .build()
        try {
            nm.notify(Reminders.notificationId(taskId), n)
        } catch (_: SecurityException) {
            // Notification permission revoked between the check and the post.
        }
    }
}

/** Alarms are cleared on reboot (and on app update): set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val zen = (context.applicationContext as ZenTaskApplication).zen
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { zen.rescheduleReminders(force = true) } finally { result.finish() }
        }
    }
}
