package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HEADER = "\"Folder Name\",\"List Name\",\"Title\",\"Kind\",\"Tags\",\"Content\",\"Is Check list\",\"Start Date\",\"Due Date\",\"Reminder\",\"Repeat\",\"Priority\",\"Status\",\"Created Time\",\"Completed Time\",\"Order\",\"Timezone\",\"Is All Day\",\"Is Floating\",\"Column Name\",\"Column Order\",\"View Mode\",\"taskId\",\"parentId\",\"projectKind\""

internal val SAMPLE_CSV = "﻿\"Date: 2026-11-08+0000\"\n\"Version: 7.2\"\n\"Status: \n0 Normal\n-1 Abandoned \n2 Completed\"\n" + HEADER + "\n" +
    "\"👦P Folder\",\"🔁Routine\",\"🌞 Morning\",\"CHECKLIST\",\"\",\"▫\n▫Charge watch\n▪Stretch, \"\"twice\"\"\n\",\"Y\",\"\",\"\",\"\",\"\",\"0\",\"0\",\"2026-03-01T03:17:07+0000\",\"\",\"-9079256848783589376\",\"Asia/Ho_Chi_Minh\",\"false\",\"false\",\"☀️ Morning Routine\",\"-8070450532247011328\",\"list\",\"1\",\"\",\"TASK\"\n" +
    "\"\",\"Inbox\",\"Call bank\",\"TEXT\",\"core, vaynhanh\",\"line 1\nline 2\",\"N\",\"2026-10-30T04:00:00+0000\",\"2026-10-30T04:30:00+0000\",\"-P0DT15H0M0SP0DT9H0M0S\",\"\",\"5\",\"2\",\"2026-10-01T01:00:00+0000\",\"2026-10-30T05:00:00+0000\",\"10\",\"Asia/Saigon\",\"false\",\"false\",\"\",\"\",\"list\",\"2\",\"\",\"TASK\"\n" +
    "\"\",\"Inbox\",\"Sub of call\",\"TEXT\",\"\",\"\",\"N\",\"\",\"\",\"PT0S\",\"\",\"0\",\"0\",\"2026-10-02T01:00:00+0000\",\"\",\"11\",\"\",\"\",\"false\",\"Not Sectioned\",\"\",\"list\",\"3\",\"2\",\"TASK\"\n"

class TickTickCsvTest {
    private val s = TickTickCsv.parse(SAMPLE_CSV)
    private val tasks = TickTickMapper.map(s).map { it.entity }.filterIsInstance<com.vuhpquang.zentask.model.Task>()

    @Test
    fun parsesQuotedMultilineFields() {
        assertEquals(3, s.tasks.size)
        val call = tasks.single { it.title == "Call bank" }
        assertEquals("line 1\nline 2", call.content)
        assertEquals(5, call.priority)
        assertEquals(TaskStatus.COMPLETED, call.status)
        assertEquals(1_793_332_800_000L, call.startDate)
        assertEquals(listOf("core", "vaynhanh"), call.tags)
        assertEquals("Asia/Saigon", call.timeZone)
        assertEquals(10L, call.sortOrder)
    }

    @Test
    fun checklistLinesBecomeItems() {
        val items = TickTickMapper.map(s).map { it.entity }.filterIsInstance<com.vuhpquang.zentask.model.ChecklistItem>()
        assertEquals(listOf("Charge watch", "Stretch, \"twice\""), items.sortedBy { it.sortOrder }.map { it.title })
        assertEquals(listOf(TaskStatus.OPEN, TaskStatus.COMPLETED), items.sortedBy { it.sortOrder }.map { it.status })
        val morning = tasks.single { it.title == "🌞 Morning" }
        assertEquals("", morning.desc)
        assertTrue(items.all { it.taskId == morning.id })
    }

    @Test
    fun splitsConcatenatedReminders() {
        assertEquals(listOf("TRIGGER:-P0DT15H0M0S", "TRIGGER:P0DT9H0M0S"), tasks.single { it.title == "Call bank" }.reminders)
        assertEquals(listOf("TRIGGER:PT0S"), tasks.single { it.title == "Sub of call" }.reminders)
    }

    @Test
    fun inboxListMapsToInboxAndFoldersAndColumnsExist() {
        val call = tasks.single { it.title == "Call bank" }
        assertEquals(Project.INBOX_ID, call.projectId)
        val sub = tasks.single { it.title == "Sub of call" }
        assertEquals(call.id, sub.parentId)
        assertNull(sub.columnId, "TickTick's default 'Not Sectioned' is no column")
        assertEquals(listOf("🔁Routine"), s.projects.map { it.name })
        assertEquals(listOf("👦P Folder"), s.groups.map { it.name })
        assertEquals(listOf("☀️ Morning Routine"), s.columns.map { it.name })
        val morning = tasks.single { it.title == "🌞 Morning" }
        assertEquals(TickTickMapper.id(s.columns.single().id), morning.columnId)
    }

    @Test
    fun idsAreStableAcrossParses() {
        assertEquals(s.tasks.map { it.id }, TickTickCsv.parse(SAMPLE_CSV).tasks.map { it.id })
        assertEquals(s.tasks.size, s.tasks.map { it.id }.toSet().size)
    }

    @Test
    fun csvImportWorksOnceAndIsRefusedAfterApiSync() {
        val store = ZenStore(createSqlDriver(null), wall = { 1L })
        val sync = TickTickSync(store, wall = { 1L })
        val plan = sync.importCsv(s)
        assertTrue(plan.created >= 5)
        assertEquals(3, store.list(EntityTypes.task).size)
        assertEquals(0, sync.importCsv(s).created, "importing the same file again duplicates nothing")
        sync.apply(TickTickSnapshot(inboxId = "inbox1"), confirmMassTrash = true)
        assertFailsWith<IllegalStateException> { sync.importCsv(s) }
    }

    @Test
    fun rejectsTextWithoutHeader() {
        assertFailsWith<IllegalArgumentException> { TickTickCsv.parse("hello,world\n1,2\n") }
    }
}
