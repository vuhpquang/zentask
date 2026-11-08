package com.vuhpquang.zentask.focus

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.FocusRecord
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private val store = ZenStore(createSqlDriver(null)) { 1L }
    private val focus = FocusActions(store)

    @Test
    fun recordsAreSavedAndTiny() {
        val r = focus.record(ms("2026-11-08T09:00"), ms("2026-11-08T09:25"), FocusRecord.KIND_POMO, "task-1", "")
        assertEquals(25, r.minutes)
        assertEquals("task-1", store.get(EntityTypes.focusRecord, r.id)!!.taskId)
        assertNull(focus.recordOrNull(ms("2026-11-08T10:00"), ms("2026-11-08T10:00:30"), FocusRecord.KIND_STOPWATCH, null, ""), "under a minute isn't kept")
    }

    @Test
    fun statsCountTodayAndTotals() {
        focus.record(ms("2026-11-07T09:00"), ms("2026-11-07T09:25"), FocusRecord.KIND_POMO, null, "")
        focus.record(ms("2026-11-08T09:00"), ms("2026-11-08T09:25"), FocusRecord.KIND_POMO, null, "")
        focus.record(ms("2026-11-08T14:00"), ms("2026-11-08T14:40"), FocusRecord.KIND_STOPWATCH, null, "")
        val s = FocusStats.of(store.list(EntityTypes.focusRecord), kotlinx.datetime.LocalDate(2026, 11, 8), zone)
        assertEquals(1, s.todayPomos)
        assertEquals(65, s.todayMinutes)
        assertEquals(2, s.totalPomos)
        assertEquals(90, s.totalMinutes)
        assertEquals(listOf("2026-11-08", "2026-11-07"), s.days.map { it.date.toString() }, "newest day first")
        assertEquals(2, s.days.first().records.size)
        assertTrue(s.days.first().records.first().start > s.days.first().records.last().start, "newest first within a day")
    }

    @Test
    fun pomodoroCycleHasALongBreakEveryFourth() {
        val p = PomodoroPlan(focusMinutes = 25, shortBreak = 5, longBreak = 15, longEvery = 4)
        assertEquals(listOf(5, 5, 5, 15, 5), (1..5).map { p.breakAfter(it) })
    }
}
