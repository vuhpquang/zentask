package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A local notification to schedule. [id] is stable for the same item + trigger, so rescheduling replaces it
 * (every planned id starts with `zt.`). [kind] is [KIND_TASK], [KIND_HABIT], [KIND_COUNTDOWN] or [KIND_SUMMARY];
 * [taskId] is the task (task kind), [habitId] + [stamp] the habit and its day (habit kind), [countdownId] the countdown.
 */
data class PlannedReminder(
    val id: String,
    val taskId: String,
    val title: String,
    val fireAt: Long,
    val kind: String = KIND_TASK,
    val body: String = "",
    val habitId: String = "",
    val stamp: Int = 0,
    val countdownId: String = "",
) {
    companion object {
        const val KIND_TASK = "task"
        const val KIND_HABIT = "habit"
        const val KIND_COUNTDOWN = "countdown"
        const val KIND_SUMMARY = "summary"
    }
}

/**
 * Which reminders fire in the next [days] days (spec §11). A trigger is an offset from the task's date
 * (start, else due): `TRIGGER:-PT15M` = 15 minutes before. All-day tasks are anchored at their local
 * midnight; there, "on time" (`PT0S`) and 09:00 triggers mean [allDayMinutes] after midnight (09:00 by default). Open, live tasks only, soonest first, at most [limit]
 * (macOS keeps 64 pending notifications per app).
 */
object ReminderPlanner {
    private const val HOUR = 3_600_000L
    private val DURATION = Regex("""^(-)?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    fun upcoming(tasks: List<Task>, now: Instant, zone: TimeZone, days: Int = 14, limit: Int = 60, allDayMinutes: Int = 9 * 60): List<PlannedReminder> {
        val from = now.toEpochMilliseconds()
        val until = from + days * 24 * HOUR
        val out = mutableListOf<PlannedReminder>()
        for (t in tasks) {
            if (t.deleted || t.trashedAt != null || !t.isOpen || t.reminders.isEmpty()) continue
            val anchor = t.startDate ?: t.dueDate ?: continue
            for (trigger in t.reminders.distinct()) {
                val offset = offsetMillis(trigger) ?: continue
                val fire = anchor + if (t.isAllDay) allDayOffset(offset, allDayMinutes) else offset
                if (fire in (from + 1)..until) out += PlannedReminder("zt.${t.id}.${trigger.removePrefix("TRIGGER:")}", t.id, t.title, fire)
            }
        }
        return out.sortedBy { it.fireAt }.take(limit)
    }

    /**
     * All-day reminders at 09:00 (TickTick's default and every preset) or "on time" fire at [allDayMinutes]
     * (Settings → Notifications) on the same day; other explicit times (17:50) stay as set.
     */
    private fun allDayOffset(offset: Long, allDayMinutes: Int): Long {
        val day = 24 * HOUR
        val timeOfDay = ((offset % day) + day) % day
        return if (offset == 0L || timeOfDay == 9 * HOUR) offset - timeOfDay + allDayMinutes * 60_000L else offset
    }

    /** ISO 8601 duration (`-PT15M`, `P0DT9H0M0S`, `-P1W`) with or without `TRIGGER:` → millis; null if unreadable. */
    fun offsetMillis(trigger: String): Long? {
        val m = DURATION.matchEntire(trigger.trim().removePrefix("TRIGGER:")) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        val ms = (w.toLongOrNull() ?: 0) * 7 * 24 * HOUR + (d.toLongOrNull() ?: 0) * 24 * HOUR + (h.toLongOrNull() ?: 0) * HOUR +
            (min.toLongOrNull() ?: 0) * 60_000L + (s.toLongOrNull() ?: 0) * 1000L
        return if (sign == "-") -ms else ms
    }
}
