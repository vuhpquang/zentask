package com.vuhpquang.zentask.habits

import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** yyyyMMdd ↔ LocalDate (check-in stamps, countdown dates). */
object Stamp {
    fun of(d: LocalDate): Int = d.year * 10_000 + (d.month.ordinal + 1) * 100 + d.day
    fun date(stamp: Int): LocalDate? = runCatching { LocalDate(stamp / 10_000, stamp / 100 % 100, stamp % 100) }.getOrNull()
}

/**
 * When a habit is due, from TickTick's rule format: `FREQ=DAILY;INTERVAL=n`, `FREQ=WEEKLY;BYDAY=MO,WE`,
 * or `FREQ=WEEKLY;TT_TIMES=n` (n times a week, any days). Anything else counts as daily.
 */
object HabitSchedule {
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
    private val SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private fun parts(h: Habit): Map<String, String> =
        h.repeatRule.orEmpty().removePrefix("RRULE:").split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()

    private fun weekdays(h: Habit): Set<DayOfWeek> =
        parts(h)["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.trim().uppercase()] }?.toSet().orEmpty()

    /** N times a week, or null for day-based schedules. */
    fun weekTarget(h: Habit): Int? = parts(h)["TT_TIMES"]?.toIntOrNull()?.takeIf { it > 0 }

    /** Every-N-days habits count from their start date, or the day they were created ([zone]). */
    fun isDue(h: Habit, date: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
        val p = parts(h)
        if (p["FREQ"] == "WEEKLY") {
            if (weekTarget(h) != null) return true
            val days = weekdays(h)
            return days.isEmpty() || date.dayOfWeek in days
        }
        val interval = p["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 } ?: return true
        val start = h.targetStartDate?.let(Stamp::date)
            ?: h.createdAt.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
            ?: return true
        return start.daysUntil(date).mod(interval) == 0
    }

    fun describe(h: Habit): String {
        weekTarget(h)?.let { return "$it times a week" }
        val days = weekdays(h)
        if (parts(h)["FREQ"] == "WEEKLY" && days.isNotEmpty() && days.size < 7) {
            return days.sortedBy { it.isoDayNumber }.joinToString(", ") { SHORT[it.isoDayNumber - 1] }
        }
        val interval = parts(h)["INTERVAL"]?.toIntOrNull() ?: 1
        return if (interval > 1) "Every $interval days" else "Every day"
    }
}

/** Streaks and totals; [unit] is "days", or "weeks" for N-times-a-week habits. */
data class HabitStats(val currentStreak: Int, val longestStreak: Int, val totalDays: Int, val unit: String) {
    companion object {
        /** Days walked back at most (bounds the work for a stray ancient check-in). */
        private const val MAX_DAYS = 3660

        fun of(h: Habit, done: Set<LocalDate>, today: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): HabitStats {
            val target = HabitSchedule.weekTarget(h)
            if (target != null) return weekly(target, done.filterTo(HashSet()) { it <= today }, today)
            // Walk back over due days; today not done yet doesn't break the streak.
            var current = 0
            var d = today
            var first = true
            val floor = today.minus(MAX_DAYS, DateTimeUnit.DAY)
            val earliest = maxOf(done.minOrNull() ?: today, floor)
            while (d >= earliest) {
                if (HabitSchedule.isDue(h, d, zone)) {
                    if (d in done) current++ else if (!(first && d == today)) break
                }
                first = false
                d = d.minus(1, DateTimeUnit.DAY)
            }
            var longest = 0
            var run = 0
            d = earliest
            while (d <= today) {
                if (HabitSchedule.isDue(h, d, zone)) {
                    if (d in done) { run++; longest = maxOf(longest, run) } else if (d != today) run = 0
                }
                d = d.plus(1, DateTimeUnit.DAY)
            }
            return HabitStats(current, maxOf(longest, current), done.count { it <= today }, "days")
        }

        private fun weekly(target: Int, done: Set<LocalDate>, today: LocalDate): HabitStats {
            fun monday(d: LocalDate) = d.minus(d.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            val counts = done.groupingBy { monday(it) }.eachCount()
            val thisWeek = monday(today)
            var current = 0
            var w = thisWeek
            if ((counts[w] ?: 0) < target) w = w.minus(7, DateTimeUnit.DAY) // the current week may still be in progress
            while ((counts[w] ?: 0) >= target) { current++; w = w.minus(7, DateTimeUnit.DAY) }
            var longest = 0
            var run = 0
            var x = counts.keys.minOrNull() ?: thisWeek
            while (x <= thisWeek) {
                if ((counts[x] ?: 0) >= target) { run++; longest = maxOf(longest, run) } else if (x != thisWeek) run = 0
                x = x.plus(7, DateTimeUnit.DAY)
            }
            return HabitStats(current, maxOf(longest, current), done.count { it <= today }, "weeks")
        }
    }
}

/**
 * Habit edits and check-ins. A check-in's id is `chk_<habitId>_<yyyyMMdd>` so two devices checking in
 * on the same day merge; an existing (e.g. imported) check-in for the day is reused.
 */
class HabitActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun create(name: String, icon: String?, color: String?, type: String, goal: Double, step: Double, unit: String, repeatRule: String): Habit {
        val last = store.list(EntityTypes.habit).maxOfOrNull { it.sortOrder }
        val h = Habit(
            id = Uuid.random().toString(), createdAt = wall(), name = name.trim(), icon = icon, color = color, type = type,
            goal = goal, step = step, unit = unit, repeatRule = repeatRule, sortOrder = (last ?: 0) + (1L shl 32),
        )
        store.put(EntityTypes.habit, h)
        return h
    }

    /** Saves an edit made from [original] (the copy the editor opened): only the fields changed in the editor are written. */
    fun update(original: Habit, name: String, icon: String?, color: String?, type: String, goal: Double, step: Double, unit: String, repeatRule: String) {
        val edited = original.copy(name = name.trim(), icon = icon, color = color, type = type, goal = goal, step = step, unit = unit, repeatRule = repeatRule)
        if (store.get(EntityTypes.habit, original.id)?.deleted == false) store.put(EntityTypes.habit, edited, base = original)
    }

    fun archive(id: String) {
        val now = wall()
        store.update(EntityTypes.habit, id) { it.copy(archivedAt = now) }
    }

    fun unarchive(id: String) {
        store.update(EntityTypes.habit, id) { it.copy(archivedAt = null) }
    }

    /** Permanent: the habit and its check-ins. */
    fun delete(id: String) {
        store.inTransaction {
            store.idsWhere(EntityTypes.habitCheckin, "habitId", id).forEach { store.delete(EntityTypes.habitCheckin, it) }
            store.delete(EntityTypes.habit, id)
        }
    }

    /** Every live check-in for the day (normally one; a ZenTask one and an imported TickTick one can coexist). */
    fun checkins(habitId: String, date: LocalDate): List<HabitCheckin> {
        val stamp = Stamp.of(date)
        return store.idsWhere(EntityTypes.habitCheckin, "habitId", habitId).mapNotNull { store.get(EntityTypes.habitCheckin, it) }
            .filter { it.stamp == stamp && !it.deleted }
            .sortedWith(compareBy({ it.ticktickId == null }, { it.id }))
    }

    /** The day's canonical check-in: the imported one when there is one, so TickTick and ZenTask agree. */
    fun checkin(habitId: String, date: LocalDate): HabitCheckin? = checkins(habitId, date).firstOrNull()

    fun doneDays(habitId: String): Set<LocalDate> =
        store.idsWhere(EntityTypes.habitCheckin, "habitId", habitId).mapNotNull { store.get(EntityTypes.habitCheckin, it) }
            .filter { it.status == 2 && !it.deleted }.mapNotNullTo(HashSet()) { Stamp.date(it.stamp) }

    /**
     * Yes/no habit: toggles done (any check-in of the day counts; undoing clears them all). Number habit:
     * adds [amount] (default the habit's step) to the day's total; done once the goal is reached.
     * Value, status and time are always written together ([CHECKIN_FIELDS]) so a merge keeps them paired.
     */
    fun checkIn(habitId: String, date: LocalDate, amount: Double?) {
        store.inTransaction {
            val h = store.get(EntityTypes.habit, habitId)?.takeIf { !it.deleted } ?: return@inTransaction
            val now = wall()
            val all = checkins(habitId, date)
            if (h.type == Habit.TYPE_REAL) {
                val total = (all.maxOfOrNull { it.value } ?: 0.0) + (amount ?: h.step)
                val status = if (total >= h.goal) 2 else if (total > 0) 1 else 0
                write(habitId, date, all.firstOrNull(), now) { it.copy(value = maxOf(0.0, total), goal = h.goal, status = status, checkinAt = now) }
                all.drop(1).forEach { reset(it) }
            } else if (all.any { it.status == 2 }) {
                all.forEach { reset(it) }
            } else {
                write(habitId, date, all.firstOrNull(), now) { it.copy(value = 1.0, goal = h.goal, status = 2, checkinAt = now) }
            }
        }
    }

    fun clear(habitId: String, date: LocalDate) {
        store.inTransaction { checkins(habitId, date).forEach { reset(it) } }
    }

    private fun write(habitId: String, date: LocalDate, existing: HabitCheckin?, now: Long, change: (HabitCheckin) -> HabitCheckin) {
        if (existing != null) {
            store.update(EntityTypes.habitCheckin, existing.id, CHECKIN_FIELDS, change)
        } else {
            store.put(EntityTypes.habitCheckin, change(HabitCheckin(id = "chk_${habitId}_${Stamp.of(date)}", createdAt = now, habitId = habitId, stamp = Stamp.of(date))))
        }
    }

    private fun reset(c: HabitCheckin) {
        if (c.value != 0.0 || c.status != 0) store.update(EntityTypes.habitCheckin, c.id, CHECKIN_FIELDS) { it.copy(value = 0.0, status = 0) }
    }

    private companion object {
        val CHECKIN_FIELDS = setOf("value", "status", "checkinAt")
    }
}

/** The next (or last) occurrence of a countdown. [daysLeft] < 0 means "days since". */
data class CountdownInfo(val date: LocalDate, val daysLeft: Int, val years: Int?)

object CountdownQueries {
    fun next(c: Countdown, today: LocalDate): CountdownInfo {
        val original = Stamp.date(c.date) ?: return CountdownInfo(today, 0, null)
        if (c.repeat != "yearly") return CountdownInfo(original, today.daysUntil(original), null)
        if (original >= today) return CountdownInfo(original, today.daysUntil(original), 0) // the first one is still ahead
        var year = today.year
        var occurrence = onYear(original, year)
        if (occurrence < today) { year++; occurrence = onYear(original, year) }
        return CountdownInfo(occurrence, today.daysUntil(occurrence), year - original.year)
    }

    /** The original's month/day in [year]; 29 February becomes the 28th in common years. */
    fun onYear(original: LocalDate, year: Int): LocalDate {
        val month = original.month.ordinal + 1
        return runCatching { LocalDate(year, month, original.day) }.getOrElse { LocalDate(year, month, 28) }
    }
}

class CountdownActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun create(name: String, date: Int, type: String, repeat: String, color: String?, icon: String?, note: String, showInCalendar: Boolean): Countdown {
        val last = store.list(EntityTypes.countdown).maxOfOrNull { it.sortOrder }
        val c = Countdown(
            id = Uuid.random().toString(), createdAt = wall(), name = name.trim(), date = date, type = type, repeat = repeat,
            color = color, icon = icon, note = note, showInCalendar = showInCalendar, sortOrder = (last ?: 0) + (1L shl 32),
        )
        store.put(EntityTypes.countdown, c)
        return c
    }

    /** Saves an edit made from [original]: only the fields changed in the editor are written. */
    fun update(original: Countdown, name: String, date: Int, type: String, repeat: String, color: String?, icon: String?, note: String, showInCalendar: Boolean) {
        val edited = original.copy(name = name.trim(), date = date, type = type, repeat = repeat, color = color, icon = icon, note = note, showInCalendar = showInCalendar)
        if (store.get(EntityTypes.countdown, original.id)?.deleted == false) store.put(EntityTypes.countdown, edited, base = original)
    }

    fun archive(id: String) {
        val now = wall()
        store.update(EntityTypes.countdown, id) { it.copy(archivedAt = now) }
    }

    fun unarchive(id: String) {
        store.update(EntityTypes.countdown, id) { it.copy(archivedAt = null) }
    }

    fun delete(id: String) = store.delete(EntityTypes.countdown, id)
}
