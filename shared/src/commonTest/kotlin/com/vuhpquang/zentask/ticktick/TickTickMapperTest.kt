package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.ProjectGroup
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A `batch/check/0` response trimmed to what we use, with extra keys TickTick sends. */
internal const val BATCH_JSON = """
{
  "checkPoint": 123, "inboxId": "inbox115781412",
  "projectGroups": [{"id": "g1", "name": "Personal stuff", "sortOrder": -5, "showAll": true, "etag": "x"}],
  "projectProfiles": [
    {"id": "p1", "name": "Works", "color": "#4772FA", "groupId": null, "kind": "TASK", "viewMode": "kanban",
     "sortOrder": 10, "sortType": "dueDate", "closed": null, "isOwner": true, "inAll": true},
    {"id": "p2", "name": "Routine", "groupId": "g1", "kind": "TASK", "viewMode": "list", "sortOrder": 20, "closed": true}
  ],
  "syncTaskBean": {"update": [
    {"id": "t1", "projectId": "p1", "title": "Ship P2", "content": "notes", "desc": "", "kind": "TEXT",
     "priority": 5, "status": 0, "startDate": "2026-10-30T04:00:00.000+0000", "dueDate": "2026-10-30T04:30:00.000+0000",
     "isAllDay": false, "timeZone": "Asia/Ho_Chi_Minh", "isFloating": false, "repeatFlag": "",
     "reminders": [{"id": "r1", "trigger": "TRIGGER:-PT15M"}, {"id": "r2", "trigger": "PT0S"}],
     "tags": ["Work", "core"], "sortOrder": -1099511627776, "columnId": "c1", "parentId": null,
     "createdTime": "2026-10-01T01:00:00.000+0000", "modifiedTime": "2026-10-02T01:00:00.000+0000",
     "pinnedTime": "2026-10-03T01:00:00Z", "etag": "abc", "deleted": 0, "focusSummaries": [], "progress": 0},
    {"id": "t2", "projectId": "inbox115781412", "title": "Pack", "kind": "CHECKLIST", "priority": 0, "status": 0,
     "parentId": "t1", "repeatFlag": "RRULE:FREQ=DAILY;INTERVAL=1", "repeatFrom": "2",
     "items": [
       {"id": "i1", "title": "Shoes", "status": 1, "sortOrder": 0, "completedTime": "2026-10-05T01:00:00.000+0000"},
       {"id": "i2", "title": "Racket", "status": 0, "sortOrder": 1}
     ]}
  ]},
  "tags": [{"name": "work", "label": "Work", "color": "#FF0000", "parent": null, "sortOrder": 1, "sortType": "project"}]
}
"""

class TickTickMapperTest {
    private val snapshot = TickTickSnapshot.fromBatch(TickTickJson.decodeFromString(TtBatch.serializer(), BATCH_JSON))
    private val mapped = TickTickMapper.map(snapshot)
    private inline fun <reified T> all(): List<T> = mapped.map { it.entity }.filterIsInstance<T>()

    @Test
    fun mapsTaskFields() {
        val t = all<Task>().single { it.ticktickId == "t1" }
        assertEquals("tt_t1", t.id)
        assertEquals("tt_p1", t.projectId)
        assertEquals("tt_c1", t.columnId)
        assertNull(t.parentId)
        assertEquals("Ship P2", t.title)
        assertEquals("notes", t.content)
        assertEquals(5, t.priority)
        assertEquals(TaskStatus.OPEN, t.status)
        assertEquals(1_793_332_800_000L, t.startDate)
        assertEquals(1_793_334_600_000L, t.dueDate)
        assertEquals(false, t.isAllDay)
        assertEquals("Asia/Ho_Chi_Minh", t.timeZone)
        assertNull(t.repeatRule)
        assertEquals(listOf("TRIGGER:-PT15M", "TRIGGER:PT0S"), t.reminders)
        assertEquals(listOf("work", "core"), t.tags)
        assertEquals(-1_099_511_627_776L, t.sortOrder)
        assertEquals(1_790_816_400_000L, t.createdAt)
        assertEquals(1_790_989_200_000L, t.pinnedAt)
    }

    @Test
    fun inboxTasksGoToInbox() {
        val t = all<Task>().single { it.ticktickId == "t2" }
        assertEquals(Project.INBOX_ID, t.projectId)
        assertEquals("tt_t1", t.parentId)
        assertEquals("RRULE:FREQ=DAILY;INTERVAL=1", t.repeatRule)
        assertEquals(Task.KIND_CHECKLIST, t.kind)
        val inbox = all<Project>().single { it.id == Project.INBOX_ID }
        assertTrue(inbox.isInbox)
        assertEquals("inbox115781412", inbox.ticktickId)
    }

    @Test
    fun projectsAndGroups() {
        val works = all<Project>().single { it.id == "tt_p1" }
        assertEquals("Works", works.name)
        assertEquals("#4772FA", works.color)
        assertEquals("kanban", works.viewMode)
        assertEquals(false, works.closed)
        val routine = all<Project>().single { it.id == "tt_p2" }
        assertEquals("tt_g1", routine.groupId)
        assertEquals(true, routine.closed)
        assertEquals("Personal stuff", all<ProjectGroup>().single().name)
    }

    @Test
    fun checklistItemsBecomeEntities() {
        val items = all<ChecklistItem>().sortedBy { it.sortOrder }
        assertEquals(listOf("tt_i1", "tt_i2"), items.map { it.id })
        assertEquals(listOf("tt_t2", "tt_t2"), items.map { it.taskId })
        assertEquals(listOf(TaskStatus.COMPLETED, TaskStatus.OPEN), items.map { it.status })
        assertEquals(1_791_162_000_000L, items[0].completedAt)
    }

    @Test
    fun parsesAllTickTickTimeForms() {
        assertEquals(1_793_332_800_000L, TickTickMapper.parseTime("2026-10-30T04:00:00.000+0000"))
        assertEquals(1_793_332_800_000L, TickTickMapper.parseTime("2026-10-30T04:00:00+0000"))
        assertEquals(1_793_332_800_000L, TickTickMapper.parseTime("2026-10-30T04:00:00Z"))
        assertEquals(1_793_307_600_000L, TickTickMapper.parseTime("2026-10-30T04:00:00+0700"))
        assertNull(TickTickMapper.parseTime(""))
        assertNull(TickTickMapper.parseTime(null))
        assertNull(TickTickMapper.parseTime("yesterday"))
    }

    @Test
    fun idsAreDeterministic() {
        assertEquals(mapped, TickTickMapper.map(snapshot))
        assertTrue(mapped.all { it.entity.ticktickId != null })
    }

    @Test
    fun tagsAreKeyedByLowercaseName() {
        val tag = all<Tag>().single()
        assertEquals("tt_tag_work", tag.id)
        assertEquals("work", tag.name)
        assertEquals("Work", tag.label)
        assertEquals("#FF0000", tag.color)
    }

    @Test
    fun habitAndCheckinMapping() {
        val s = snapshot.copy(
            habits = listOf(TtHabit(id = "h1", name = "Run", type = "Real", goal = 5.0, unit = "km", status = 1, archivedTime = "2026-10-01T00:00:00.000+0000")),
            habitsAvailable = true,
            checkins = listOf(TtCheckin(id = "k1", habitId = "h1", checkinStamp = 20261101, value = 3.0, goal = 5.0, status = 1)),
        )
        val m = TickTickMapper.map(s)
        val habit = m.map { it.entity }.filterIsInstance<Habit>().single()
        assertEquals(Habit.TYPE_REAL, habit.type)
        assertEquals(5.0, habit.goal)
        assertEquals(1_790_812_800_000L, habit.archivedAt)
        val checkin = m.map { it.entity }.filterIsInstance<HabitCheckin>().single()
        assertEquals("tt_h1", checkin.habitId)
        assertEquals(20261101, checkin.stamp)
        assertEquals(3.0, checkin.value)
    }

    @Test
    fun everyMappedTypeMatchesItsEntity() {
        for (m in mapped) {
            val expected = EntityTypes.all.single { it.serializer.descriptor.serialName == m.entity::class.qualifiedName }
            assertEquals(expected, m.type)
        }
    }
}
