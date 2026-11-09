package com.vuhpquang.zentask.notify

import com.vuhpquang.zentask.ZenTaskCore
import com.vuhpquang.zentask.model.Habit
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationCoreTest {
    private val core = ZenTaskCore.open(null)

    @Test
    fun prefsAreStoredAndUsedForNewTasks() {
        assertEquals(NotificationPrefs(), core.notificationPrefs())
        core.setNotificationPrefs(NotificationPrefs(defaultAllDay = "TRIGGER:PT0S", summary = false))
        assertEquals(false, core.notificationPrefs().summary)
        val t = core.actions.create("Pay rent", filter = null, due = 1_800_000_000_000, allDay = true)
        assertEquals(listOf("TRIGGER:PT0S"), t.reminders)
    }

    @Test
    fun markDoneFromANotificationNeverUndoes() {
        val yes = core.habitActions.create("Run", null, null, Habit.TYPE_BOOLEAN, 1.0, 1.0, "", "RRULE:FREQ=DAILY")
        val stamp = core.todayStamp()
        core.habitMarkDone(yes.id, stamp)
        core.habitMarkDone(yes.id, stamp)
        val day = com.vuhpquang.zentask.habits.Stamp.date(stamp)!!
        assertEquals(2, core.habitActions.checkin(yes.id, day)?.status)

        val water = core.habitActions.create("Water", null, null, Habit.TYPE_REAL, 8.0, 1.0, "cups", "RRULE:FREQ=DAILY")
        core.habitAdd(water.id, stamp, 3.0)
        core.habitMarkDone(water.id, stamp)
        val c = core.habitActions.checkin(water.id, day)!!
        assertEquals(8.0, c.value)
        assertEquals(2, c.status)
    }
}
