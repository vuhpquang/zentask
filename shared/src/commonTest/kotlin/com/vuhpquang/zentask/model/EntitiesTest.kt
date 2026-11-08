package com.vuhpquang.zentask.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class EntitiesTest {
    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(type: EntityType<*>) {
        val t = type as EntityType<Entity>
        val minimal = t.fromFields(mapOf("id" to JsonPrimitive("x")))
        assertEquals("x", minimal.id)
        assertEquals(minimal, t.fromFields(t.toFields(minimal)), "round trip of ${type.name}")
    }

    @Test
    fun everyEntityRoundTripsThroughFields() {
        EntityTypes.all.forEach { roundTrip(it) }
    }

    @Test
    fun toFieldsIncludesNullsAndDefaults() {
        val fields = EntityTypes.task.toFields(Task(id = "t1"))
        assertEquals(JsonNull, fields["dueDate"])
        assertEquals(JsonPrimitive(0), fields["priority"])
        assertEquals(JsonArray(emptyList()), fields["tags"])
        assertEquals(JsonPrimitive(false), fields["deleted"])
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val task = EntityTypes.task.fromFields(
            mapOf("id" to JsonPrimitive("t1"), "title" to JsonPrimitive("a"), "futureField" to JsonPrimitive(1)),
        )
        assertEquals("a", task.title)
    }

    @Test
    fun registryNamesAreStable() {
        assertEquals(
            listOf(
                "projectGroup", "project", "column", "task", "checklistItem", "tag", "filter",
                "habit", "habitSection", "habitCheckin", "countdown", "preference",
                "calendarAccount", "calendarSource", "calendarEvent",
            ),
            EntityTypes.all.map { it.name },
        )
        assertSame(EntityTypes.task, EntityTypes.byName("task"))
        assertEquals(null, EntityTypes.byName("nope"))
    }

    @Test
    fun calendarTypesAreServerOwned() {
        assertEquals(
            setOf("calendarAccount", "calendarSource", "calendarEvent"),
            EntityTypes.all.filterNot { it.clientWritable }.map { it.name }.toSet(),
        )
    }

    @Test
    fun draftTaskHasInboxAndUuid() {
        val a = Task.draft("a", now = 5)
        val b = Task.draft("b", now = 5)
        assertEquals(Project.INBOX_ID, a.projectId)
        assertEquals("inbox", Project.INBOX_ID)
        assertEquals(5, a.createdAt)
        assertEquals(36, a.id.length)
        assertNotEquals(a.id, b.id)
        assertEquals(Priority.NONE, a.priority)
        assertEquals(TaskStatus.OPEN, a.status)
        assertFalse(a.deleted)
    }
}
