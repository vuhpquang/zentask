package com.vuhpquang.zentask.focus

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.FocusRecord
import com.vuhpquang.zentask.store.ZenStore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Pomodoro lengths: a long break after every [longEvery] pomos. */
data class PomodoroPlan(val focusMinutes: Int = 25, val shortBreak: Int = 5, val longBreak: Int = 15, val longEvery: Int = 4) {
    /** The break after the [n]th pomo of a run (1-based). */
    fun breakAfter(n: Int): Int = if (longEvery > 0 && n % longEvery == 0) longBreak else shortBreak
}

/** Saves finished focus sessions (the running timer lives on the device; only results sync). */
class FocusActions(
    private val store: ZenStore,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    fun record(start: Long, end: Long, kind: String, taskId: String?, note: String): FocusRecord {
        val r = FocusRecord(id = Uuid.random().toString(), createdAt = wall(), taskId = taskId, start = start, end = maxOf(end, start), kind = kind, note = note.trim())
        store.put(EntityTypes.focusRecord, r)
        return r
    }

    /** Like [record], but sessions under a minute aren't kept (a mis-click on Start). */
    fun recordOrNull(start: Long, end: Long, kind: String, taskId: String?, note: String): FocusRecord? =
        if (end - start < 60_000) null else record(start, end, kind, taskId, note)

    fun delete(id: String) = store.delete(EntityTypes.focusRecord, id)
}

data class FocusDay(val date: LocalDate, val records: List<FocusRecord>, val minutes: Int)

data class FocusStats(
    val todayPomos: Int,
    val todayMinutes: Int,
    val totalPomos: Int,
    val totalMinutes: Int,
    /** Newest day first, records newest first. */
    val days: List<FocusDay>,
) {
    companion object {
        fun of(records: List<FocusRecord>, today: LocalDate, zone: TimeZone): FocusStats {
            val live = records.filter { !it.deleted && it.end > it.start }
            fun day(r: FocusRecord) = Instant.fromEpochMilliseconds(r.start).toLocalDateTime(zone).date
            val todays = live.filter { day(it) == today }
            val days = live.groupBy(::day).entries.sortedByDescending { it.key }
                .map { (d, rs) -> FocusDay(d, rs.sortedByDescending { it.start }, rs.sumOf { it.minutes }) }
            return FocusStats(
                todayPomos = todays.count { it.kind == FocusRecord.KIND_POMO },
                todayMinutes = todays.sumOf { it.minutes },
                totalPomos = live.count { it.kind == FocusRecord.KIND_POMO },
                totalMinutes = live.sumOf { it.minutes },
                days = days,
            )
        }
    }
}
