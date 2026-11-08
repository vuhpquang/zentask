package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Entity
import com.vuhpquang.zentask.model.EntityType
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.ProjectGroup
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import kotlin.time.Instant

/** A ZenTask entity produced from TickTick data, with its type. */
data class Mapped(val type: EntityType<*>, val entity: Entity)

/**
 * TickTick snapshot → ZenTask entities. Ids are deterministic (`tt_<ticktickId>`, tags `tt_tag_<name>`,
 * TickTick's Inbox → `inbox`) so every device importing the same data produces the same records.
 */
object TickTickMapper {
    fun id(ticktickId: String): String = "tt_$ticktickId"
    fun tagId(name: String): String = "tt_tag_${name.lowercase()}"

    fun map(s: TickTickSnapshot): List<Mapped> {
        val out = mutableListOf<Mapped>()
        fun project(ttId: String?): String? = when {
            ttId.isNullOrEmpty() -> null
            ttId == s.inboxId || ttId.startsWith("inbox") -> Project.INBOX_ID
            else -> id(ttId)
        }
        s.inboxId?.let {
            out += Mapped(EntityTypes.project, Project(id = Project.INBOX_ID, ticktickId = it, name = "Inbox", isInbox = true))
        }
        s.groups.forEach { g ->
            out += Mapped(EntityTypes.projectGroup, ProjectGroup(id = id(g.id), ticktickId = g.id, name = g.name.orEmpty(), sortOrder = g.sortOrder ?: 0))
        }
        s.projects.forEach { p ->
            out += Mapped(
                EntityTypes.project,
                Project(
                    id = id(p.id), ticktickId = p.id, name = p.name.orEmpty(), color = p.color,
                    groupId = p.groupId?.takeIf { it.isNotEmpty() && it != "NONE" }?.let(::id),
                    kind = p.kind ?: Project.KIND_TASK, viewMode = p.viewMode ?: "list",
                    sortOrder = p.sortOrder ?: 0, sortType = p.sortType, closed = p.closed == true,
                ),
            )
        }
        s.columns.forEach { c ->
            out += Mapped(
                EntityTypes.column,
                Column(id = id(c.id), ticktickId = c.id, projectId = project(c.projectId) ?: Project.INBOX_ID, name = c.name.orEmpty(), sortOrder = c.sortOrder ?: 0),
            )
        }
        s.tags.forEach { t ->
            out += Mapped(
                EntityTypes.tag,
                Tag(
                    id = tagId(t.name), ticktickId = t.name, name = t.name.lowercase(), label = t.label ?: t.name,
                    color = t.color, parent = t.parent?.lowercase(), sortOrder = t.sortOrder ?: 0, sortType = t.sortType,
                ),
            )
        }
        s.tasks.forEach { t ->
            out += Mapped(
                EntityTypes.task,
                Task(
                    id = id(t.id), ticktickId = t.id,
                    createdAt = parseTime(t.createdTime) ?: 0,
                    projectId = project(t.projectId) ?: Project.INBOX_ID,
                    parentId = t.parentId?.takeIf { it.isNotEmpty() }?.let(::id),
                    columnId = t.columnId?.takeIf { it.isNotEmpty() }?.let(::id),
                    title = t.title.orEmpty(), content = t.content.orEmpty(), desc = t.desc.orEmpty(),
                    kind = t.kind ?: Task.KIND_TEXT, priority = t.priority ?: 0, status = t.status ?: TaskStatus.OPEN,
                    startDate = parseTime(t.startDate), dueDate = parseTime(t.dueDate),
                    isAllDay = t.isAllDay == true, timeZone = t.timeZone, isFloating = t.isFloating == true,
                    repeatRule = t.repeatFlag?.takeIf { it.isNotBlank() }, repeatFrom = t.repeatFrom?.takeIf { it.isNotBlank() },
                    repeatTaskId = t.repeatTaskId?.takeIf { it.isNotEmpty() }?.let(::id),
                    reminders = t.reminders.orEmpty().mapNotNull { it.trigger?.takeIf(String::isNotBlank)?.let(::trigger) },
                    tags = t.tags.orEmpty().map { it.lowercase() },
                    sortOrder = t.sortOrder ?: 0,
                    completedAt = parseTime(t.completedTime), pinnedAt = parseTime(t.pinnedTime),
                ),
            )
            t.items.orEmpty().forEach { i ->
                out += Mapped(
                    EntityTypes.checklistItem,
                    ChecklistItem(
                        id = id(i.id), ticktickId = i.id, taskId = id(t.id), title = i.title.orEmpty(),
                        status = if ((i.status ?: 0) != 0) TaskStatus.COMPLETED else TaskStatus.OPEN,
                        sortOrder = i.sortOrder ?: 0, startDate = parseTime(i.startDate), isAllDay = i.isAllDay == true,
                        completedAt = parseTime(i.completedTime),
                    ),
                )
            }
        }
        s.habits.forEach { h ->
            out += Mapped(
                EntityTypes.habit,
                Habit(
                    id = id(h.id), ticktickId = h.id, createdAt = parseTime(h.createdTime) ?: 0,
                    name = h.name.orEmpty(), icon = h.iconRes, color = h.color,
                    type = if (h.type.equals("Real", ignoreCase = true)) Habit.TYPE_REAL else Habit.TYPE_BOOLEAN,
                    goal = h.goal ?: 1.0, step = h.step ?: 1.0, unit = h.unit.orEmpty(), repeatRule = h.repeatRule,
                    reminders = h.reminders.orEmpty(), sectionId = h.sectionId?.let(::id), sortOrder = h.sortOrder ?: 0,
                    archivedAt = parseTime(h.archivedTime) ?: if (h.status == 1) 0L else null,
                    targetDays = h.targetDays ?: 0, targetStartDate = h.targetStartDate?.takeIf { it > 0 },
                    encouragement = h.encouragement.orEmpty(),
                ),
            )
        }
        s.checkins.forEach { c ->
            out += Mapped(
                EntityTypes.habitCheckin,
                HabitCheckin(
                    id = id(c.id), ticktickId = c.id, habitId = c.habitId?.let(::id).orEmpty(), stamp = c.checkinStamp ?: 0,
                    value = c.value ?: 0.0, goal = c.goal ?: 0.0, status = c.status ?: 0, checkinAt = parseTime(c.checkinTime),
                ),
            )
        }
        s.countdowns.forEach { c ->
            out += Mapped(
                EntityTypes.countdown,
                Countdown(
                    id = id(c.id), ticktickId = c.id, name = c.name.orEmpty(), date = c.date ?: 0,
                    type = c.type?.uppercase() ?: "COUNTDOWN", color = c.color, note = c.remark.orEmpty(), sortOrder = c.sortOrder ?: 0,
                ),
            )
        }
        return out
    }

    /** TickTick reminder trigger, stored with the `TRIGGER:` prefix (spec §6). */
    fun trigger(value: String): String = if (value.startsWith("TRIGGER:")) value else "TRIGGER:$value"

    private val OFFSET_NO_COLON = Regex("""([+-]\d{2})(\d{2})$""")

    /** `2026-10-30T04:00:00.000+0000`, `…+0000`, `…Z`, `…+07:00` → epoch millis; null when blank/unparsable. */
    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val iso = s.trim().replace(OFFSET_NO_COLON, "$1:$2")
        return try {
            Instant.parse(iso).toEpochMilliseconds()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
