package com.vuhpquang.zentask.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Data model shaped after TickTick's (spec §6). Property names are persisted field names: a file
// format shared by every client and the server — never rename one, add a new property instead.
// Enum-like values are plain Int/String constants so a value written by a newer client still decodes.
// Times are epoch millis; calendar days are Int yyyyMMdd.

/** Fields every synced record has. */
interface Entity {
    val id: String
    val createdAt: Long
    val updatedAt: Long
    /** Tombstone: permanently deleted. Moving to Trash is [Task.trashedAt] / [Project.trashedAt]. */
    val deleted: Boolean
    /** TickTick's own id when the record came from TickTick (keeps import idempotent). */
    val ticktickId: String?
}

/** TickTick's priority values. */
object Priority {
    const val NONE = 0
    const val LOW = 1
    const val MEDIUM = 3
    const val HIGH = 5
}

/** TickTick's task / checklist item status values. TickTick's legacy `1` is stored as-is. */
object TaskStatus {
    const val OPEN = 0
    const val COMPLETED = 2
    const val WONT_DO = -1
}

@Serializable
data class ProjectGroup(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    val sortOrder: Long = 0,
    val collapsed: Boolean = false,
) : Entity

@Serializable
data class Project(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    val color: String? = null,
    val icon: String? = null,
    val groupId: String? = null,
    /** [KIND_TASK] or [KIND_NOTE]. */
    val kind: String = KIND_TASK,
    /** "list", "kanban" or "timeline"; v1 renders list only. */
    val viewMode: String = "list",
    val sortOrder: Long = 0,
    val sortType: String? = null,
    val isInbox: Boolean = false,
    /** Archived. */
    val closed: Boolean = false,
    val trashedAt: Long? = null,
) : Entity {
    companion object {
        /** Fixed id so every device shares one Inbox. */
        const val INBOX_ID = "inbox"
        const val KIND_TASK = "TASK"
        const val KIND_NOTE = "NOTE"
    }
}

/** A section inside a list. */
@Serializable
data class Column(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val projectId: String = Project.INBOX_ID,
    val name: String = "",
    val sortOrder: Long = 0,
) : Entity

@Serializable
data class Task(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val projectId: String = Project.INBOX_ID,
    /** Parent task when this is a subtask. */
    val parentId: String? = null,
    val columnId: String? = null,
    val title: String = "",
    /** Markdown body. */
    val content: String = "",
    /** Description of a checklist-kind task. */
    val desc: String = "",
    /** [KIND_TEXT], [KIND_CHECKLIST] or [KIND_NOTE]. */
    val kind: String = KIND_TEXT,
    val priority: Int = Priority.NONE,
    val status: Int = TaskStatus.OPEN,
    val startDate: Long? = null,
    val dueDate: Long? = null,
    val isAllDay: Boolean = false,
    val timeZone: String? = null,
    val isFloating: Boolean = false,
    /** RFC 5545 RRULE. */
    val repeatRule: String? = null,
    /** "due" or "completion". */
    val repeatFrom: String? = null,
    /** Completed copy of a recurring task: id of the task it was generated from (spec §7.1). */
    val repeatTaskId: String? = null,
    /** Reminder triggers, e.g. `TRIGGER:-PT15M`. */
    val reminders: List<String> = emptyList(),
    /** Tag names (lowercase keys of [Tag.name]). */
    val tags: List<String> = emptyList(),
    val sortOrder: Long = 0,
    val completedAt: Long? = null,
    val pinnedAt: Long? = null,
    val trashedAt: Long? = null,
    /** Location reminder: a [Place] id and when to remind, [Place.ARRIVE] or [Place.LEAVE]. */
    val placeId: String? = null,
    val placeTrigger: String? = null,
) : Entity {
    companion object {
        const val KIND_TEXT = "TEXT"
        const val KIND_CHECKLIST = "CHECKLIST"
        const val KIND_NOTE = "NOTE"
    }
}

/** A new open task with a fresh UUID. */
fun Task.Companion.draft(title: String, projectId: String = Project.INBOX_ID, now: Long): Task =
    Task(id = Uuid.random().toString(), createdAt = now, updatedAt = now, title = title, projectId = projectId)

@Serializable
data class ChecklistItem(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val taskId: String = "",
    val title: String = "",
    val status: Int = TaskStatus.OPEN,
    val sortOrder: Long = 0,
    val startDate: Long? = null,
    val isAllDay: Boolean = false,
    val completedAt: Long? = null,
) : Entity

@Serializable
data class Tag(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    /** Logical key, lowercase. */
    val name: String = "",
    /** Display label. */
    val label: String = "",
    val color: String? = null,
    /** Parent tag name for nested tags. */
    val parent: String? = null,
    val sortOrder: Long = 0,
    val sortType: String? = null,
) : Entity

/** A custom smart list. */
@Serializable
data class Filter(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    /** Rule as raw JSON (lists, tags, priority, date range, keyword). */
    val rule: String = "{}",
    val sortOrder: Long = 0,
    /** An emoji shown in the sidebar (optional). */
    val icon: String? = null,
) : Entity

@Serializable
data class Habit(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    /** [TYPE_BOOLEAN] or [TYPE_REAL]. */
    val type: String = TYPE_BOOLEAN,
    val goal: Double = 1.0,
    val step: Double = 1.0,
    val unit: String = "",
    val repeatRule: String? = null,
    val reminders: List<String> = emptyList(),
    val sectionId: String? = null,
    val sortOrder: Long = 0,
    val archivedAt: Long? = null,
    val targetDays: Int = 0,
    /** yyyyMMdd. */
    val targetStartDate: Int? = null,
    val encouragement: String = "",
    /**
     * For N-times-a-week / -month habits: reminders start on this day of the period (ISO weekday 1–7, or day
     * of month 1–31) and repeat daily until the period's target is met. Null = every day of the period.
     */
    val remindFrom: Int? = null,
) : Entity {
    companion object {
        const val TYPE_BOOLEAN = "BOOLEAN"
        const val TYPE_REAL = "REAL"
    }
}

@Serializable
data class HabitSection(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    val sortOrder: Long = 0,
) : Entity

@Serializable
data class HabitCheckin(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val habitId: String = "",
    /** yyyyMMdd. */
    val stamp: Int = 0,
    val value: Double = 0.0,
    val goal: Double = 0.0,
    /** 0 none, 1 unfinished, 2 done. */
    val status: Int = 0,
    val checkinAt: Long? = null,
) : Entity

@Serializable
data class Countdown(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    /** yyyyMMdd. */
    val date: Int = 0,
    /** COUNTDOWN, ANNIVERSARY, BIRTHDAY or HOLIDAY. */
    val type: String = "COUNTDOWN",
    /** "yearly" or "none". */
    val repeat: String = "none",
    val icon: String? = null,
    val color: String? = null,
    val note: String = "",
    val sortOrder: Long = 0,
    val archivedAt: Long? = null,
    val showInCalendar: Boolean = true,
    /**
     * Reminders as "<days before>@HH:mm" (e.g. "7@09:00", "0@08:30"). Null = the defaults in
     * NotificationPrefs (on the day + countdownDaysBefore, at countdownMinutes); empty = none.
     */
    val reminders: List<String>? = null,
) : Entity

/** A file on a task. The bytes live on the server under [fileId] (their SHA-256) and in each device's cache. */
@Serializable
data class Attachment(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val taskId: String = "",
    val fileId: String = "",
    val name: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) : Entity {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** A saved place for location reminders: map point + radius, and Wi‑Fi names that also mean "here". */
@Serializable
data class Place(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    /** [KIND_HOME], [KIND_WORK] or [KIND_CUSTOM]. */
    val kind: String = KIND_CUSTOM,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** Metres. */
    val radius: Double = 150.0,
    val wifi: List<String> = emptyList(),
    val sortOrder: Long = 0,
) : Entity {
    companion object {
        const val KIND_HOME = "home"
        const val KIND_WORK = "work"
        const val KIND_CUSTOM = "custom"
        const val ARRIVE = "arrive"
        const val LEAVE = "leave"
    }
}

/** A reusable task: title, notes, priority, tags, checklist and subtasks to stamp out as a new task. */
@Serializable
data class TaskTemplate(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val name: String = "",
    val title: String = "",
    val content: String = "",
    val priority: Int = 0,
    val tags: List<String> = emptyList(),
    val checklist: List<String> = emptyList(),
    val subtasks: List<String> = emptyList(),
    val sortOrder: Long = 0,
) : Entity

/** A finished focus session (Pomodoro or stopwatch), optionally on a task. */
@Serializable
data class FocusRecord(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val taskId: String? = null,
    val start: Long = 0,
    val end: Long = 0,
    /** [KIND_POMO] or [KIND_STOPWATCH]. */
    val kind: String = KIND_POMO,
    val note: String = "",
) : Entity {
    val minutes: Int get() = ((end - start) / 60_000).toInt()

    companion object {
        const val KIND_POMO = "pomo"
        const val KIND_STOPWATCH = "stopwatch"
    }
}

/** A synced setting; [value] is raw JSON. The record id is the setting key. */
@Serializable
data class Preference(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val key: String = "",
    val value: String = "null",
) : Entity

/** Server-owned (read-only for clients). */
@Serializable
data class CalendarAccount(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val email: String = "",
    /** GOOGLE or ICAL. */
    val provider: String = "GOOGLE",
    val status: String = "",
) : Entity

/** Server-owned; `visible` will be the one client-editable field (P5). */
@Serializable
data class CalendarSource(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val accountId: String = "",
    val remoteId: String = "",
    val name: String = "",
    val color: String? = null,
    val visible: Boolean = true,
) : Entity

/** Server-owned (read-only for clients). */
@Serializable
data class CalendarEvent(
    override val id: String,
    override val createdAt: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
    override val ticktickId: String? = null,
    val sourceId: String = "",
    val remoteId: String = "",
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val isAllDay: Boolean = false,
    val timeZone: String? = null,
    val recurringEventId: String? = null,
    val status: String = "",
) : Entity
