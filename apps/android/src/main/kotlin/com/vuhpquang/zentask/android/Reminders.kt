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
import com.vuhpquang.zentask.habits.Stamp
import com.vuhpquang.zentask.notify.Snooze
import com.vuhpquang.zentask.tasks.PlannedReminder
import com.vuhpquang.zentask.tasks.isOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notifications as exact alarms, planned by the shared `NotificationPlanner` (the same ones the Mac shows): task
 * and habit reminders, countdowns and the morning summary.
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
        if (r != null) putReminder(intent, r)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Shows the same notification again at [at]. Snoozes use their own `zs.` ids, so [sync] leaves them alone. */
    fun snooze(fire: Intent, at: Long) {
        val id = fire.data?.schemeSpecificPart.orEmpty().removePrefix("zs.")
        val again = Intent(fire).setAction(ACTION_FIRE).setData(android.net.Uri.parse("zentask-reminder:zs.$id"))
        val pi = PendingIntent.getBroadcast(context, 0, again, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
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
        const val ACTION_CHECK_IN = "com.vuhpquang.zentask.REMINDER_CHECK_IN"
        const val ACTION_SNOOZE = "com.vuhpquang.zentask.REMINDER_SNOOZE"
        const val EXTRA_TASK = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val EXTRA_BODY = "body"
        const val EXTRA_HABIT = "habitId"
        const val EXTRA_STAMP = "stamp"
        const val EXTRA_COUNTDOWN = "countdownId"
        const val EXTRA_SNOOZE = "snooze"
        /** The notification's own id (`zt.…`), carried to the snooze so it can be shown again. */
        const val EXTRA_ID = "id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Task and habit reminders, countdowns and the daily summary"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun putReminder(intent: Intent, r: PlannedReminder): Intent = intent
            .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_KIND, r.kind).putExtra(EXTRA_TASK, r.taskId).putExtra(EXTRA_TITLE, r.title)
            .putExtra(EXTRA_BODY, r.body).putExtra(EXTRA_HABIT, r.habitId).putExtra(EXTRA_STAMP, r.stamp).putExtra(EXTRA_COUNTDOWN, r.countdownId)

        /** Stable per task, so a second reminder for the same task replaces the first notification. */
        fun notificationId(taskId: String): Int = taskId.hashCode()

        /** Stable per item: a habit's reminders replace each other, as do a countdown's and the summary. */
        fun notificationId(intent: Intent): Int = when (intent.getStringExtra(EXTRA_KIND) ?: PlannedReminder.KIND_TASK) {
            PlannedReminder.KIND_HABIT -> ("habit:" + intent.getStringExtra(EXTRA_HABIT)).hashCode()
            PlannedReminder.KIND_COUNTDOWN -> ("countdown:" + intent.getStringExtra(EXTRA_COUNTDOWN)).hashCode()
            PlannedReminder.KIND_SUMMARY -> SUMMARY_NOTIFICATION_ID
            else -> notificationId(intent.getStringExtra(EXTRA_TASK).orEmpty())
        }

        const val SUMMARY_NOTIFICATION_ID = 0x5A40
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
        when (intent.action) {
            Reminders.ACTION_DONE -> {
                val taskId = intent.getStringExtra(Reminders.EXTRA_TASK) ?: return
                zen.core.task(taskId)?.takeIf { it.isOpen }?.let { zen.core.actions.complete(taskId) }
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(intent))
            }
            Reminders.ACTION_CHECK_IN -> {
                val habitId = intent.getStringExtra(Reminders.EXTRA_HABIT) ?: return
                zen.core.habitMarkDone(habitId, intent.getIntExtra(Reminders.EXTRA_STAMP, 0))
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(intent))
            }
            Reminders.ACTION_SNOOZE -> {
                val option = intent.getStringExtra(Reminders.EXTRA_SNOOZE) ?: Snooze.FIFTEEN_MINUTES
                zen.snooze(intent, zen.core.snoozeUntil(option))
                NotificationManagerCompat.from(context).cancel(Reminders.notificationId(intent))
            }
            Reminders.ACTION_FIRE -> fire(context, zen, intent)
        }
    }

    private fun fire(context: Context, zen: ZenApp, intent: Intent) {
        val prefs = zen.core.notificationPrefs()
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(Reminders.EXTRA_BODY).orEmpty()
        when (intent.getStringExtra(Reminders.EXTRA_KIND) ?: PlannedReminder.KIND_TASK) {
            PlannedReminder.KIND_HABIT -> {
                val habitId = intent.getStringExtra(Reminders.EXTRA_HABIT) ?: return
                val day = Stamp.date(intent.getIntExtra(Reminders.EXTRA_STAMP, 0)) ?: return
                // Planned earlier; skip if it has since been done, archived or deleted.
                val habit = zen.core.habitRows().firstOrNull { it.habit.id == habitId }?.habit
                if (habit == null || habit.archivedAt != null || zen.core.habitActions.checkins(habitId, day).any { it.status == 2 }) return
                show(context, intent, habit.name.ifBlank { title }, body, prefs.sound, primary = "Check in" to Reminders.ACTION_CHECK_IN)
            }
            PlannedReminder.KIND_COUNTDOWN ->
                show(context, intent, title, body, prefs.sound, primary = null, snooze = true)
            PlannedReminder.KIND_SUMMARY ->
                show(context, intent, title, body, prefs.sound, primary = null, snooze = false)
            else -> {
                val taskId = intent.getStringExtra(Reminders.EXTRA_TASK) ?: return
                val task = zen.core.task(taskId)
                if (task == null || !task.isOpen || task.deleted || task.trashedAt != null) return
                show(context, intent, task.title.ifBlank { title }, "Task reminder", prefs.sound, primary = "Done" to Reminders.ACTION_DONE)
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

    /** Posts a reminder: tapping opens the task (or the app); [primary] is Done / Check in; snoozes 15 min and 1 hour. */
    private fun show(context: Context, fire: Intent, title: String, text: String, sound: Boolean, primary: Pair<String, String>?, snooze: Boolean = true) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val nid = Reminders.notificationId(fire)
        val taskId = fire.getStringExtra(Reminders.EXTRA_TASK).orEmpty()
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (taskId.isNotEmpty()) launch.setData(android.net.Uri.parse("zentask-task:$taskId")).putExtra(MainActivity.EXTRA_OPEN_TASK, taskId)
        val open = PendingIntent.getActivity(context, nid, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, request: Int, extra: Intent.() -> Unit = {}): PendingIntent = PendingIntent.getBroadcast(
            context, request,
            Intent(fire).setClass(context, ReminderReceiver::class.java).setAction(action).also(extra),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!sound)
            .setAutoCancel(true)
            .setContentIntent(open)
        primary?.let { (label, act) -> n.addAction(0, label, action(act, nid)) }
        if (snooze) {
            n.addAction(0, "15 min", action(Reminders.ACTION_SNOOZE, nid + 1) { putExtra(Reminders.EXTRA_SNOOZE, Snooze.FIFTEEN_MINUTES) })
            n.addAction(0, "1 hour", action(Reminders.ACTION_SNOOZE, nid + 2) { putExtra(Reminders.EXTRA_SNOOZE, Snooze.ONE_HOUR) })
        }
        try {
            nm.notify(nid, n.build())
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
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) showUpdated(context, zen.updater.currentVersion)
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { zen.rescheduleReminders(force = true) } finally { result.finish() }
        }
    }

    /** An in-app update quits the app; say it worked and offer to open it again. */
    private fun showUpdated(context: Context, version: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ZenTask updated to $version")
            .setContentText("Tap to open")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(UPDATED_NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    private companion object {
        const val UPDATED_NOTIFICATION_ID = 0x5A3F
    }
}
