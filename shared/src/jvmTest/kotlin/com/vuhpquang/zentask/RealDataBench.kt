package com.vuhpquang.zentask

import com.vuhpquang.zentask.tasks.TaskIndex
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.measureTime

/** Timing on a copy of a real database: `ZT_BENCH_DB=/path/copy.db ./gradlew :shared:jvmTest --tests '*RealDataBench*'`. */
class RealDataBench {
    @Test
    fun timings() {
        val path = System.getenv("ZT_BENCH_DB") ?: return
        val core = ZenTaskCore.open(path)
        val day = Clock.System.now().toEpochMilliseconds()
        repeat(2) { round ->
            val index: TaskIndex
            val tIndex = measureTime { index = kotlinx.coroutines.runBlocking { core.loadIndex() } }
            val tWeek = measureTime { core.calendarDays(day, 7) }
            val tMonth = measureTime { core.calendarDays(day, 42) }
            val tHabits = measureTime { core.habitRows() }
            val tCounts = measureTime { core.sidebarCounts(index) }
            val tRem = measureTime { core.plannedReminders(index) }
            val st = core.store
            val e = com.vuhpquang.zentask.model.EntityTypes
            val tEv = measureTime { st.listOverlapping(e.calendarEvent, day - 3 * 86_400_000L, day + 9 * 86_400_000L) }
            val tTasks = measureTime { st.list(e.task) }
            val tChk = measureTime { st.list(e.habitCheckin) }
            val tPref = measureTime { core.calendarView(); core.hiddenCalendars() }
            println("BENCH parts events=$tEv tasks=$tTasks checkins=$tChk prefs=$tPref")
            println("BENCH round=$round index=$tIndex week=$tWeek month=$tMonth habits=$tHabits counts=$tCounts reminders=$tRem")
        }
    }
}
