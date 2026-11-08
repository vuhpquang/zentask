package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.Task
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** A local notification to schedule. [id] is stable for the same task + trigger, so rescheduling replaces it. */
data class PlannedReminder(val id: String, val taskId: String, val title: String, val fireAt: Long)

/**
 * Which reminders fire in the next [days] days (spec §11). A trigger is an offset from the task's date
 * (start, else due): `TRIGGER:-PT15M` = 15 minutes before. All-day tasks are anchored at their local
 * midnight; there, "on time" (`PT0S`) means 09:00. Open, live tasks only, soonest first, at most [limit]
 * (macOS keeps 64 pending notifications per app).
 */
object ReminderPlanner {
    private const val HOUR = 3_600_000L
    private const val ALL_DAY_ON_TIME = 9 * HOUR
    private val DURATION = Regex("""^(-)?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    fun upcoming(tasks: List<Task>, now: Instant, zone: TimeZone, days: Int = 14, limit: Int = 60): List<PlannedReminder> {
        val from = now.toEpochMilliseconds()
        val until = from + days * 24 * HOUR
        val out = mutableListOf<PlannedReminder>()
        for (t in tasks) {
            if (t.deleted || t.trashedAt != null || !t.isOpen || t.reminders.isEmpty()) continue
            val anchor = t.startDate ?: t.dueDate ?: continue
            for (trigger in t.reminders.distinct()) {
                val offset = offsetMillis(trigger) ?: continue
                val fire = anchor + if (t.isAllDay && offset == 0L) ALL_DAY_ON_TIME else offset
                if (fire in (from + 1)..until) out += PlannedReminder("zt.${t.id}.${trigger.removePrefix("TRIGGER:")}", t.id, t.title, fire)
            }
        }
        return out.sortedBy { it.fireAt }.take(limit)
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
