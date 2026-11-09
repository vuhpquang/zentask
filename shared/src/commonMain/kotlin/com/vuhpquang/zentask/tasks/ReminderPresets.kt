package com.vuhpquang.zentask.tasks

/**
 * The reminder choices the apps offer. All-day tasks are anchored at local midnight (see [ReminderPlanner]),
 * so "1 day early at 9:00" is `-PT15H`; the planner moves 9:00 to the all-day reminder time in the settings. [convert] maps choices when a task switches between all-day and timed,
 * dropping ones without a counterpart (a reminder must never silently change its meaning).
 */
object ReminderPresets {
    val timed: List<Pair<String, String>> = listOf(
        "TRIGGER:PT0S" to "On time",
        "TRIGGER:-PT5M" to "5 minutes early",
        "TRIGGER:-PT30M" to "30 minutes early",
        "TRIGGER:-PT1H" to "1 hour early",
        "TRIGGER:-P1D" to "1 day early",
    )

    val allDay: List<Pair<String, String>> = listOf(
        "TRIGGER:PT9H" to "On the day",
        "TRIGGER:-PT15H" to "1 day early",
        "TRIGGER:-P1DT15H" to "2 days early",
        "TRIGGER:-P6DT15H" to "1 week early",
    )

    /** Timed trigger ↔ its all-day counterpart. */
    private val pairs = listOf(
        "TRIGGER:PT0S" to "TRIGGER:PT9H",
        "TRIGGER:-P1D" to "TRIGGER:-PT15H",
        "TRIGGER:-P2D" to "TRIGGER:-P1DT15H",
        "TRIGGER:-P7D" to "TRIGGER:-P6DT15H",
    )

    fun convert(triggers: List<String>, toAllDay: Boolean): List<String> = triggers.mapNotNull { t ->
        if (toAllDay) {
            pairs.firstOrNull { it.first == t }?.second ?: t.takeIf { a -> allDay.any { it.first == a } }
        } else {
            pairs.firstOrNull { it.second == t }?.first ?: t.takeIf { a -> timed.any { it.first == a } }
        }
    }.distinct()

    fun title(trigger: String): String? = (timed + allDay).firstOrNull { it.first == trigger }?.second
}
