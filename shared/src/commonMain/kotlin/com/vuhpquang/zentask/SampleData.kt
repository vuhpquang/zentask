package com.vuhpquang.zentask

import com.vuhpquang.zentask.model.CalendarAccount
import com.vuhpquang.zentask.model.CalendarEvent
import com.vuhpquang.zentask.model.CalendarSource
import com.vuhpquang.zentask.model.Entity
import com.vuhpquang.zentask.model.EntityType
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.toFields
import com.vuhpquang.zentask.sync.SyncRecord

/** Sample calendar data for UI snapshots and previews (in-memory stores only, never the real database). */
object SampleData {
    fun addCalendar(core: ZenTaskCore, dayStart: Long) {
        fun at(hours: Double, day: Int = 0) = dayStart + day * 86_400_000L + (hours * 3_600_000).toLong()
        fun <T : Entity> rec(type: EntityType<T>, value: T): SyncRecord {
            val fields = type.toFields(value)
            return SyncRecord(type.name, value.id, fields, fields.mapValues { "000000000000001-000000-sample" })
        }
        core.store.applyRemote(
            listOf(
                rec(EntityTypes.calendarAccount, CalendarAccount(id = "acc", email = "me@example.com")),
                rec(EntityTypes.calendarSource, CalendarSource(id = "s1", accountId = "acc", name = "Personal", color = "#2E9E88")),
                rec(EntityTypes.calendarSource, CalendarSource(id = "s2", accountId = "acc", name = "Work", color = "#3B82F6")),
                rec(EntityTypes.calendarEvent, CalendarEvent(id = "e1", sourceId = "s2", title = "Standup", start = at(9.0), end = at(9.25))),
                rec(EntityTypes.calendarEvent, CalendarEvent(id = "e2", sourceId = "s2", title = "Design review", start = at(14.0, 1), end = at(15.5, 1))),
                rec(EntityTypes.calendarEvent, CalendarEvent(id = "e5", sourceId = "s2", title = "1:1", start = at(14.5, 1), end = at(15.0, 1))),
                rec(EntityTypes.calendarEvent, CalendarEvent(id = "e3", sourceId = "s1", title = "Gym", start = at(18.0, 1), end = at(19.0, 1))),
                rec(EntityTypes.calendarEvent, CalendarEvent(id = "e4", sourceId = "s1", title = "Trip", start = at(0.0, 2), end = at(0.0, 4), isAllDay = true)),
            ),
        )
        core.actions.create("Write release notes", null, priority = 5, due = at(10.5), allDay = false)
    }
}
