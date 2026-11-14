package com.vuhpquang.zentask.tasks

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.store.ZenStore
import com.vuhpquang.zentask.store.createSqlDriver
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemplatesTest {
    private val wall = 1_800_000_000_000L
    private val store = ZenStore(createSqlDriver(null), wall = { wall })
    private val actions = TaskActions(store, wall = { wall }, zone = { TimeZone.of("Asia/Ho_Chi_Minh") })
    private val templates = TemplateActions(store, actions, wall = { wall })

    @Test
    fun saveAndUseATemplate() {
        val trip = actions.create("Pack for a trip", filter = null, priority = 5, tags = listOf("travel"))
        actions.setContent(trip.id, "Check the weather")
        actions.addItem(trip.id, "Passport")
        actions.addItem(trip.id, "Charger")
        actions.addSubtask(trip.id, "Book taxi")
        val tpl = templates.saveFrom(trip.id, "Business trip")!!
        assertEquals(listOf("Passport", "Charger"), tpl.checklist)
        assertEquals(listOf("Book taxi"), tpl.subtasks)

        val made = templates.use(tpl.id, filter = null, projectId = "work")!!
        assertEquals("Pack for a trip", made.title)
        assertEquals("work", made.projectId)
        assertEquals(5, made.priority)
        assertEquals(listOf("travel"), made.tags)
        assertEquals("Check the weather", made.content)
        val items = store.idsWhere(EntityTypes.checklistItem, "taskId", made.id).mapNotNull { store.get(EntityTypes.checklistItem, it) }.sortedBy { it.sortOrder }
        assertEquals(listOf("Passport", "Charger"), items.map { it.title })
        val subs = store.idsWhere(EntityTypes.task, "parentId", made.id).mapNotNull { store.get(EntityTypes.task, it) }
        assertEquals(listOf("Book taxi"), subs.map { it.title })

        templates.delete(tpl.id)
        assertEquals(emptyList(), templates.list())
        assertNull(templates.use(tpl.id, filter = null))
    }
}
