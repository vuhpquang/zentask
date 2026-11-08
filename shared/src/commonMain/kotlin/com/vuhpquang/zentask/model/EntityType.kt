package com.vuhpquang.zentask.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One kind of synced record. [name] is the persisted entity name (file format: never rename).
 * [clientWritable] is false for records only the server produces (calendar data).
 */
class EntityType<T : Entity>(
    val name: String,
    val serializer: KSerializer<T>,
    val clientWritable: Boolean = true,
) {
    override fun toString(): String = "EntityType($name)"
}

object EntityTypes {
    val projectGroup = EntityType("projectGroup", ProjectGroup.serializer())
    val project = EntityType("project", Project.serializer())
    val column = EntityType("column", Column.serializer())
    val task = EntityType("task", Task.serializer())
    val checklistItem = EntityType("checklistItem", ChecklistItem.serializer())
    val tag = EntityType("tag", Tag.serializer())
    val filter = EntityType("filter", Filter.serializer())
    val habit = EntityType("habit", Habit.serializer())
    val habitSection = EntityType("habitSection", HabitSection.serializer())
    val habitCheckin = EntityType("habitCheckin", HabitCheckin.serializer())
    val countdown = EntityType("countdown", Countdown.serializer())
    val preference = EntityType("preference", Preference.serializer())
    val calendarAccount = EntityType("calendarAccount", CalendarAccount.serializer(), clientWritable = false)
    val calendarSource = EntityType("calendarSource", CalendarSource.serializer(), clientWritable = false)
    val calendarEvent = EntityType("calendarEvent", CalendarEvent.serializer(), clientWritable = false)

    val all: List<EntityType<*>> = listOf(
        projectGroup, project, column, task, checklistItem, tag, filter,
        habit, habitSection, habitCheckin, countdown, preference,
        calendarAccount, calendarSource, calendarEvent,
    )

    private val byName = all.associateBy { it.name }

    fun byName(name: String): EntityType<*>? = byName[name]
}

/** The record as a field map: every property present, nulls and defaults included. */
fun <T : Entity> EntityType<T>.toFields(value: T): Map<String, JsonElement> =
    ZenJson.encodeToJsonElement(serializer, value).jsonObject

/** Decodes a field map; missing fields take their defaults. Throws `SerializationException` on bad data. */
fun <T : Entity> EntityType<T>.fromFields(fields: Map<String, JsonElement>): T =
    ZenJson.decodeFromJsonElement(serializer, JsonObject(fields))
