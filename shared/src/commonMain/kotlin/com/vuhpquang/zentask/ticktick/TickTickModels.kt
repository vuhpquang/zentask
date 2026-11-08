package com.vuhpquang.zentask.ticktick

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// TickTick web API v2 payloads, as observed (ticktick-py, the web app). Everything optional and unknown
// keys ignored: TickTick changes its private API without notice and a missing field must not fail a sync.

/** Lenient JSON for TickTick payloads (numbers as strings and back, nulls for missing values). */
val TickTickJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class TtBatch(
    val inboxId: String? = null,
    val projectGroups: List<TtGroup>? = null,
    val projectProfiles: List<TtProject>? = null,
    val syncTaskBean: TtTaskBean? = null,
    val tags: List<TtTag>? = null,
)

@Serializable
data class TtTaskBean(val update: List<TtTask>? = null)

@Serializable
data class TtGroup(val id: String, val name: String? = null, val sortOrder: Long? = null)

@Serializable
data class TtProject(
    val id: String,
    val name: String? = null,
    val color: String? = null,
    val groupId: String? = null,
    val kind: String? = null,
    val viewMode: String? = null,
    val sortOrder: Long? = null,
    val sortType: String? = null,
    val closed: Boolean? = null,
)

@Serializable
data class TtColumn(val id: String, val projectId: String? = null, val name: String? = null, val sortOrder: Long? = null)

@Serializable
data class TtReminder(val trigger: String? = null)

@Serializable
data class TtItem(
    val id: String,
    val title: String? = null,
    /** 0 open, 1 checked. */
    val status: Int? = null,
    val sortOrder: Long? = null,
    val startDate: String? = null,
    val isAllDay: Boolean? = null,
    val completedTime: String? = null,
)

@Serializable
data class TtTask(
    val id: String,
    val projectId: String? = null,
    val parentId: String? = null,
    val columnId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val desc: String? = null,
    val kind: String? = null,
    val priority: Int? = null,
    val status: Int? = null,
    val startDate: String? = null,
    val dueDate: String? = null,
    val isAllDay: Boolean? = null,
    val timeZone: String? = null,
    val isFloating: Boolean? = null,
    val repeatFlag: String? = null,
    val repeatFrom: String? = null,
    val repeatTaskId: String? = null,
    val reminders: List<TtReminder>? = null,
    val tags: List<String>? = null,
    val items: List<TtItem>? = null,
    val sortOrder: Long? = null,
    val completedTime: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val pinnedTime: String? = null,
)

@Serializable
data class TtTag(
    val name: String,
    val label: String? = null,
    val color: String? = null,
    val parent: String? = null,
    val sortOrder: Long? = null,
    val sortType: String? = null,
)

@Serializable
data class TtHabit(
    val id: String,
    val name: String? = null,
    val iconRes: String? = null,
    val color: String? = null,
    /** "Boolean" or "Real". */
    val type: String? = null,
    val goal: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val repeatRule: String? = null,
    val reminders: List<String>? = null,
    val sectionId: String? = null,
    val sortOrder: Long? = null,
    /** 0 active, 1 archived. */
    val status: Int? = null,
    val archivedTime: String? = null,
    val targetDays: Int? = null,
    val targetStartDate: Int? = null,
    val encouragement: String? = null,
    val createdTime: String? = null,
)

@Serializable
data class TtCheckin(
    val id: String,
    val habitId: String? = null,
    val checkinStamp: Int? = null,
    val checkinTime: String? = null,
    val value: Double? = null,
    val goal: Double? = null,
    val status: Int? = null,
)

@Serializable
data class TtCountdown(
    val id: String,
    val name: String? = null,
    /** yyyyMMdd. */
    val date: Int? = null,
    val type: String? = null,
    val color: String? = null,
    val remark: String? = null,
    val sortOrder: Long? = null,
)

/**
 * Everything pulled from TickTick in one go. Optional sources carry an availability flag: a record kind
 * whose source was unavailable is never trashed/archived by a sync (see TickTickSync).
 */
data class TickTickSnapshot(
    val inboxId: String? = null,
    val groups: List<TtGroup> = emptyList(),
    val projects: List<TtProject> = emptyList(),
    val columns: List<TtColumn> = emptyList(),
    /** Open, completed and (when [wontDoAvailable]) won't-do tasks. */
    val tasks: List<TtTask> = emptyList(),
    val trashedTaskIds: Set<String> = emptySet(),
    val tags: List<TtTag> = emptyList(),
    val habits: List<TtHabit> = emptyList(),
    val checkins: List<TtCheckin> = emptyList(),
    val countdowns: List<TtCountdown> = emptyList(),
    val wontDoAvailable: Boolean = false,
    val trashAvailable: Boolean = false,
    val columnsAvailable: Boolean = false,
    val habitsAvailable: Boolean = false,
    val countdownsAvailable: Boolean = false,
) {
    companion object {
        fun fromBatch(b: TtBatch) = TickTickSnapshot(
            inboxId = b.inboxId,
            groups = b.projectGroups.orEmpty(),
            projects = b.projectProfiles.orEmpty(),
            tasks = b.syncTaskBean?.update.orEmpty(),
            tags = b.tags.orEmpty(),
        )
    }
}
