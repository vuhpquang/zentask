package com.vuhpquang.zentask.notify

import com.vuhpquang.zentask.model.ZenJson
import kotlinx.serialization.Serializable

/**
 * Notification settings, synced like every other preference (one `Preference` row, [KEY]).
 * Times are minutes after local midnight. Older or broken JSON decodes to the defaults.
 */
@Serializable
data class NotificationPrefs(
    /** Task reminders (the triggers set on each task). */
    val tasks: Boolean = true,
    /** Habit reminders at the habit's own times, on days it is due and not done yet. */
    val habits: Boolean = true,
    /** Countdown reminders on the day, and [countdownDaysBefore] days ahead. */
    val countdowns: Boolean = true,
    /** A morning summary of the day's tasks. */
    val summary: Boolean = true,
    val summaryMinutes: Int = 8 * 60,
    /** When an all-day task's "on the day" reminder fires. */
    val allDayMinutes: Int = 9 * 60,
    val countdownMinutes: Int = 9 * 60,
    /** 0 = only on the day. */
    val countdownDaysBefore: Int = 1,
    /** Trigger added to new tasks that get a time (`TRIGGER:PT0S`), or "" for none. */
    val defaultTimed: String = "",
    /** Trigger added to new all-day tasks, or "" for none. */
    val defaultAllDay: String = "",
    val sound: Boolean = true,
) {
    fun encode(): String = ZenJson.encodeToString(serializer(), this)

    companion object {
        const val KEY = "notifications"

        fun parse(json: String): NotificationPrefs = try {
            ZenJson.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            NotificationPrefs()
        }
    }
}
