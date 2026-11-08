package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Priority
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import com.vuhpquang.zentask.sync.Hlc
import com.vuhpquang.zentask.sync.SyncRecord
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskActionsTest {
    private val zone = TimeZone.of("Asia/Ho_Chi_Minh")
    private fun ms(s: String) = LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds()
    private var wall = ms("2026-11-08T10:00")
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val actions = TaskActions(store, wall = { wall }, zone = { zone })
    private fun get(id: String) = store.get(EntityTypes.task, id)!!
    private fun all() = store.list(EntityTypes.task)

    private fun recurring(rule: String = "RRULE:FREQ=DAILY", due: String = "2026-11-08T09:00") =
        Task(id = "r", title = "Stretch", projectId = "work", tags = listOf("health"), priority = Priority.MEDIUM, repeatRule = rule, dueDate = ms(due), timeZone = zone.id)
            .also { store.put(EntityTypes.task, it) }

    @Test
    fun completingRecurringTaskMakesCopyAndAdvances() {
        recurring()
        actions.complete("r")
        val original = get("r")
        assertEquals(TaskStatus.OPEN, original.status)
        assertEquals(ms("2026-11-09T09:00"), original.dueDate)
        val copy = all().single { it.id != "r" }
        assertEquals(TaskStatus.COMPLETED, copy.status)
        assertEquals("r", copy.repeatTaskId)
        assertEquals(ms("2026-11-08T09:00"), copy.dueDate)
        assertEquals(wall, copy.completedAt)
        assertEquals(listOf("health"), copy.tags)
        assertEquals("work", copy.projectId)
        assertNull(copy.repeatRule)
    }

    @Test
    fun completingTwiceMakesTwoCopiesAndKeepsOriginalOpen() {
        recurring()
        actions.complete("r")
        actions.complete("r")
        assertEquals(TaskStatus.OPEN, get("r").status)
        assertEquals(ms("2026-11-10T09:00"), get("r").dueDate)
        assertEquals(2, all().count { it.repeatTaskId == "r" })
    }

    @Test
    fun repeatFromCompletionUsesNow() {
        store.put(EntityTypes.task, Task(id = "c", repeatRule = "RRULE:FREQ=DAILY", repeatFrom = "completion", dueDate = ms("2026-11-01T09:00")))
        actions.complete("c")
        assertEquals(ms("2026-11-09T09:00"), get("c").dueDate, "date from completion, time of day from the task")
    }

    @Test
    fun endedRuleCompletesOriginal() {
        recurring(rule = "RRULE:FREQ=DAILY;COUNT=2")
        actions.complete("r")
        assertEquals(TaskStatus.OPEN, get("r").status)
        actions.complete("r")
        assertEquals(TaskStatus.COMPLETED, get("r").status)
        assertEquals(1, all().count { it.repeatTaskId == "r" }, "the last occurrence completes the original itself")
    }

    @Test
    fun unsupportedRuleCompletesOnce() {
        recurring(rule = "RRULE:FREQ=MONTHLY;BYDAY=2MO")
        actions.complete("r")
        assertEquals(TaskStatus.COMPLETED, get("r").status)
        assertEquals(1, all().size)
    }

    @Test
    fun uncompleteAndWontDoToggle() {
        store.put(EntityTypes.task, Task(id = "a"))
        actions.complete("a")
        assertEquals(wall, get("a").completedAt)
        actions.uncomplete("a")
        assertEquals(TaskStatus.OPEN, get("a").status)
        assertNull(get("a").completedAt)
        actions.wontDo("a")
        assertEquals(TaskStatus.WONT_DO, get("a").status)
        actions.uncomplete("a")
        assertEquals(TaskStatus.OPEN, get("a").status)
    }

    @Test
    fun trashCascadesToSubtasksAndRestoreBringsThemBack() {
        store.put(EntityTypes.task, Task(id = "p"))
        store.put(EntityTypes.task, Task(id = "c", parentId = "p"))
        store.put(EntityTypes.task, Task(id = "c-old", parentId = "p", trashedAt = 1))
        actions.trash("p")
        assertEquals(wall, get("p").trashedAt)
        assertEquals(wall, get("c").trashedAt)
        wall += 1_000
        actions.restore("p")
        assertNull(get("p").trashedAt)
        assertNull(get("c").trashedAt)
        assertEquals(1L, get("c-old").trashedAt, "a subtask trashed earlier on its own stays in Trash")
    }

    @Test
    fun deleteForeverOnlyFromTrash() {
        store.put(EntityTypes.task, Task(id = "a"))
        assertFailsWith<IllegalStateException> { actions.deleteForever("a") }
        actions.trash("a")
        actions.deleteForever("a")
        assertTrue(get("a").deleted)
        assertTrue(all().isEmpty())
    }

    @Test
    fun createUsesTheCurrentFilter() {
        val today = actions.create("Pay rent", TaskFilter.Smart(SmartList.TODAY))
        assertEquals(Project.INBOX_ID, today.projectId)
        assertEquals(ms("2026-11-08T00:00"), today.dueDate)
        assertTrue(today.isAllDay)
        assertEquals(zone.id, today.timeZone)
        val tomorrow = actions.create("Call", TaskFilter.Smart(SmartList.TOMORROW))
        assertEquals(ms("2026-11-09T00:00"), tomorrow.dueDate)
        val inList = actions.create("Spec", TaskFilter.InProject("work"))
        assertEquals("work", inList.projectId)
        assertNull(inList.dueDate)
        val tagged = actions.create("Read", TaskFilter.WithTag("books"))
        assertEquals(listOf("books"), tagged.tags)
        val explicit = actions.create("X", TaskFilter.Smart(SmartList.TODAY), projectId = "work", priority = Priority.HIGH, tags = listOf("A"), due = ms("2026-11-10T15:00"), allDay = false)
        assertEquals("work", explicit.projectId)
        assertEquals(Priority.HIGH, explicit.priority)
        assertEquals(listOf("a"), explicit.tags)
        assertEquals(ms("2026-11-10T15:00"), explicit.dueDate)
        assertTrue(store.list(EntityTypes.tag).any { it.name == "a" }, "a new tag gets a Tag record")
    }

    @Test
    fun fieldEdits() {
        store.put(EntityTypes.task, Task(id = "a", projectId = "inbox"))
        store.put(EntityTypes.task, Task(id = "s", parentId = "a", projectId = "inbox"))
        actions.setPriority("a", Priority.HIGH)
        actions.rename("a", "New")
        actions.setContent("a", "notes")
        actions.setDue("a", ms("2026-11-12T08:00"), allDay = false)
        actions.setTags("a", listOf("Work", "work", "home"))
        actions.move("a", "work")
        val a = get("a")
        assertEquals(Priority.HIGH, a.priority)
        assertEquals("New", a.title)
        assertEquals("notes", a.content)
        assertEquals(ms("2026-11-12T08:00"), a.dueDate)
        assertEquals(listOf("work", "home"), a.tags)
        assertEquals("work", a.projectId)
        assertEquals("work", get("s").projectId, "subtasks move with their parent")
        actions.setDue("a", null, allDay = false)
        assertNull(get("a").dueDate)
        assertNull(get("a").startDate)
    }

    @Test
    fun checklistAndSubtasks() {
        store.put(EntityTypes.task, Task(id = "a"))
        val item = actions.addItem("a", "Shoes")
        assertEquals(Task.KIND_CHECKLIST, get("a").kind)
        actions.toggleItem(item.id)
        assertEquals(TaskStatus.COMPLETED, store.get(EntityTypes.checklistItem, item.id)!!.status)
        actions.toggleItem(item.id)
        assertEquals(TaskStatus.OPEN, store.get(EntityTypes.checklistItem, item.id)!!.status)
        actions.renameItem(item.id, "Running shoes")
        assertEquals("Running shoes", store.get(EntityTypes.checklistItem, item.id)!!.title)
        actions.deleteItem(item.id)
        assertTrue(store.list(EntityTypes.checklistItem).isEmpty())
        val sub = actions.addSubtask("a", "Book flight")
        assertEquals("a", sub.parentId)
        assertNotNull(store.get(EntityTypes.task, sub.id))
    }

    @Test
    fun editsDoNotRevertRemoteChanges() {
        store.put(EntityTypes.task, Task(id = "a", title = "old"))
        store.applyRemote(listOf(SyncRecord("task", "a", mapOf("title" to JsonPrimitive("remote")), mapOf("title" to Hlc(wall + 5_000, 0, "zz").encode()))))
        wall += 10_000
        actions.setPriority("a", Priority.LOW)
        assertEquals("remote", get("a").title)
        assertEquals(Priority.LOW, get("a").priority)
    }

    @Test
    fun repeatFromCompletionKeepsAllDayAtMidnight() {
        store.put(EntityTypes.task, Task(id = "d", repeatRule = "RRULE:FREQ=DAILY", repeatFrom = "completion", isAllDay = true, timeZone = zone.id, dueDate = ms("2026-11-01T00:00")))
        wall = ms("2026-11-08T15:42")
        actions.complete("d")
        assertEquals(ms("2026-11-09T00:00"), get("d").dueDate)
    }

    @Test
    fun deleteForeverSparesSubtasksRestoredOnTheirOwn() {
        store.put(EntityTypes.task, Task(id = "p"))
        store.put(EntityTypes.task, Task(id = "s", parentId = "p"))
        actions.trash("p")
        store.update(EntityTypes.task, "s") { it.copy(trashedAt = null) } // restored from Trash on its own
        actions.deleteForever("p")
        assertTrue(get("p").deleted)
        assertTrue(!get("s").deleted, "the restored subtask survives")
    }

    @Test
    fun newTagAfterRenameDoesNotOverwriteTheRenamedOne() {
        actions.create("a", null, tags = listOf("old"))
        TagActions(store).rename("old", "new")
        actions.create("b", null, tags = listOf("old"))
        val names = store.list(EntityTypes.tag).map { it.name }.toSet()
        assertEquals(setOf("new", "old"), names)
    }
}
