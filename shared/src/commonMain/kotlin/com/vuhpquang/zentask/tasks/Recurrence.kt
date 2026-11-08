package com.vuhpquang.zentask.tasks

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The RRULE subset ZenTask can compute (P3 plan): FREQ=DAILY|WEEKLY|MONTHLY|YEARLY with INTERVAL,
 * BYDAY (weekly, plain days), BYMONTHDAY (monthly), COUNT and UNTIL. Occurrences keep the wall-clock
 * time in the task's zone (so 09:00 stays 09:00 across DST). Monthly day 31 lands on the last day of
 * shorter months, as TickTick does.
 */
object Recurrence {
    private val FREQS = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    private val KEYS = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "COUNT", "UNTIL", "WKST")
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val SHORT = mapOf(
        DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun",
    )

    private class Rule(
        val freq: String,
        val interval: Int,
        val byDay: List<DayOfWeek>,
        val byMonthDay: Int?,
        val count: Int?,
        val until: String?,
    )

    private fun parse(rule: String): Rule? {
        val body = rule.trim().removePrefix("RRULE:")
        if (body.isEmpty() || body.contains(':')) return null
        val parts = body.split(';').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0].uppercase() to p.getOrElse(1) { "" } }
            k to v
        }
        if (parts.keys.any { it !in KEYS }) return null
        val freq = parts["FREQ"]?.takeIf { it in FREQS } ?: return null
        val byDay = parts["BYDAY"]?.split(',')?.filter { it.isNotEmpty() }?.map { DAYS[it] ?: return null }.orEmpty()
        if (byDay.isNotEmpty() && freq != "WEEKLY") return null
        val byMonthDay = parts["BYMONTHDAY"]?.let { v -> v.toIntOrNull()?.takeIf { it in 1..31 } ?: return null }
        if (byMonthDay != null && freq != "MONTHLY") return null
        return Rule(
            freq = freq,
            interval = parts["INTERVAL"]?.let { v -> v.toIntOrNull()?.takeIf { it >= 1 } ?: return null } ?: 1,
            byDay = byDay,
            byMonthDay = byMonthDay,
            count = parts["COUNT"]?.let { v -> v.toIntOrNull()?.takeIf { it >= 1 } ?: return null },
            until = parts["UNTIL"],
        )
    }

    fun isSupported(rule: String): Boolean = parse(rule) != null

    /**
     * The occurrence after [from], or null when the rule has ended or isn't supported.
     * [completedCount] = occurrences completed before this one (for COUNT).
     */
    fun next(rule: String, from: Instant, zone: TimeZone, completedCount: Int): Instant? {
        val r = parse(rule) ?: return null
        if (r.count != null && completedCount + 1 >= r.count) return null
        val local = from.toLocalDateTime(zone)
        val date = nextDate(r, local.date)
        val result = LocalDateTime(date, local.time).toInstant(zone)
        r.until?.let { u -> if (beyond(u, result, date)) return null }
        return result
    }

    /** Short human description for the detail pane. */
    fun describe(rule: String): String {
        val r = parse(rule) ?: return "Repeats (custom)"
        val unit = when (r.freq) {
            "DAILY" -> "day"
            "WEEKLY" -> "week"
            "MONTHLY" -> "month"
            else -> "year"
        }
        val every = if (r.interval == 1) "Every $unit" else "Every ${r.interval} ${unit}s"
        return when {
            r.byDay.isNotEmpty() -> "$every on " + r.byDay.sortedBy { it.isoDayNumber }.joinToString(", ") { SHORT.getValue(it) }
            r.byMonthDay != null -> "$every on day ${r.byMonthDay}"
            else -> every
        }
    }

    private fun nextDate(r: Rule, date: LocalDate): LocalDate = when (r.freq) {
        "DAILY" -> date.plus(r.interval, DateTimeUnit.DAY)
        "WEEKLY" -> if (r.byDay.isEmpty()) date.plus(7 * r.interval, DateTimeUnit.DAY) else nextWeekday(r, date)
        "MONTHLY" -> {
            val month = date.plus(r.interval, DateTimeUnit.MONTH)
            clampDay(month.year, month.month.ordinal + 1, r.byMonthDay ?: date.day)
        }
        else -> {
            val year = date.year + r.interval
            clampDay(year, date.month.ordinal + 1, date.day)
        }
    }

    private fun nextWeekday(r: Rule, date: LocalDate): LocalDate {
        val weekStart = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        var d = date.plus(1, DateTimeUnit.DAY)
        repeat(7 * r.interval * 2) {
            val week = weekStart.daysUntil(d) / 7
            if (week % r.interval == 0 && d.dayOfWeek in r.byDay) return d
            d = d.plus(1, DateTimeUnit.DAY)
        }
        return date.plus(7 * r.interval, DateTimeUnit.DAY)
    }

    private fun clampDay(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate(year, month, 1)
        val length = first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
        return LocalDate(year, month, minOf(day, length))
    }

    /** UNTIL is `yyyyMMdd` (inclusive date) or `yyyyMMddTHHmmssZ` (UTC instant). */
    private fun beyond(until: String, next: Instant, nextDate: LocalDate): Boolean {
        val digits = until.filter { it.isDigit() }
        if (digits.length < 8) return false
        val untilDate = LocalDate(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        if (!until.contains('T')) return nextDate > untilDate
        val time = digits.drop(8).padEnd(6, '0')
        val untilInstant = LocalDateTime(untilDate.year, untilDate.month.ordinal + 1, untilDate.day, time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt())
            .toInstant(TimeZone.UTC)
        return next > untilInstant
    }
}
