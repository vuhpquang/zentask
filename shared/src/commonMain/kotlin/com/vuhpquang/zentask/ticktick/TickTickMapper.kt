package com.vuhpquang.zentask.ticktick

import com.vuhpquang.zentask.model.ChecklistItem
import com.vuhpquang.zentask.model.Column
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Entity
import com.vuhpquang.zentask.model.EntityType
import com.vuhpquang.zentask.lists.FilterRule
import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Filter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.model.HabitCheckin
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.ProjectGroup
import com.vuhpquang.zentask.model.Tag
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import kotlin.time.Instant

/**
 * A ZenTask entity produced from TickTick data, with its type. [absent] lists fields TickTick did not
 * send (unknown, not cleared): the sync neither writes nor baselines them. [itemsKnown] is false when a
 * task came without its checklist, so its items must not be treated as removed.
 */
data class Mapped(
    val type: EntityType<*>,
    val entity: Entity,
    val absent: Set<String> = emptySet(),
    val itemsKnown: Boolean = true,
)

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
            val absent = mutableSetOf<String>()
            if (t.tags == null) absent += "tags"
            if (t.reminders == null) absent += "reminders"
            if (t.id in s.thinTaskIds) absent += thinAbsent(t)
            out += Mapped(
                absent = absent,
                itemsKnown = t.items != null,
                type = EntityTypes.task,
                entity =
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
        s.filters.forEach { f ->
            out += Mapped(
                EntityTypes.filter,
                Filter(id = id(f.id), ticktickId = f.id, name = f.name.orEmpty(), rule = translateRule(f.rule) { pid -> project(pid) }.encode(), sortOrder = f.sortOrder ?: 0),
            )
        }
        s.countdowns.forEach { c ->
            out += Mapped(
                EntityTypes.countdown,
                Countdown(
                    id = id(c.id), ticktickId = c.id, name = c.name.orEmpty(), date = c.date ?: 0,
                    type = c.type?.uppercase() ?: "COUNTDOWN", color = c.color, note = c.remark.orEmpty(), sortOrder = c.sortOrder ?: 0,
                    repeat = if (c.repeatFlag?.contains("YEARLY", ignoreCase = true) == true || c.type?.uppercase() in setOf("BIRTHDAY", "ANNIVERSARY")) "yearly" else "none",
                ),
            )
        }
        return out
    }

    /**
     * TickTick filter rule JSON → [FilterRule]. TickTick stores conditions like
     * `{"and":[{"or":[…],"conditionName":"list"},{"not":[…],"conditionName":"tag"}, …]}`.
     * Conditions it can't read are skipped (the smart list then shows more, never crashes).
     */
    fun translateRule(json: String?, project: (String) -> String?): FilterRule {
        val root = runCatching { TickTickJson.parseToJsonElement(json.orEmpty()) as? JsonObject }.getOrNull() ?: return FilterRule()
        val any = root["or"] is JsonArray && root["and"] !is JsonArray
        val conditions = ((root["and"] ?: root["or"]) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var rule = FilterRule(match = if (any) "any" else "all")
        fun values(c: JsonObject, key: String) = (c[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        for (c in conditions) {
            when ((c["conditionName"] as? JsonPrimitive)?.contentOrNull) {
                "list" -> rule = rule.copy(
                    lists = values(c, "or").mapNotNull(project),
                    excludeLists = values(c, "not").mapNotNull(project),
                )
                "tag" -> {
                    val all = values(c, "and")
                    rule = rule.copy(
                        tags = (values(c, "or") + all).map { it.lowercase() }.distinct(),
                        tagsAll = all.isNotEmpty(),
                        excludeTags = values(c, "not").map { it.lowercase() },
                    )
                }
                "priority" -> rule = rule.copy(priorities = values(c, "or").mapNotNull { it.toIntOrNull() })
                "dueDate" -> rule = rule.copy(
                    due = values(c, "or").firstNotNullOfOrNull { v ->
                        when (v.lowercase()) {
                            "today" -> "today"
                            "tomorrow" -> "tomorrow"
                            "thisweek" -> "thisweek"
                            "nextweek" -> "nextweek"
                            "thismonth" -> "thismonth"
                            "overdue" -> "overdue"
                            "nodue", "nodate" -> "nodate"
                            "7days", "next7days", "next7day", "week" -> "next7"
                            else -> null
                        }
                    },
                )
                "keywords", "keyword" -> rule = rule.copy(keyword = values(c, "or").firstOrNull() ?: (c["value"] as? JsonPrimitive)?.contentOrNull)
            }
        }
        return rule
    }

    /** ZenTask fields of a thin task object whose TickTick source key was missing. */
    private fun thinAbsent(t: TtTask): Set<String> = buildSet {
        if (t.projectId == null) add("projectId")
        if (t.parentId == null) add("parentId")
        if (t.columnId == null) add("columnId")
        if (t.title == null) add("title")
        if (t.content == null) add("content")
        if (t.desc == null) add("desc")
        if (t.kind == null) add("kind")
        if (t.priority == null) add("priority")
        if (t.status == null) add("status")
        if (t.startDate == null) add("startDate")
        if (t.dueDate == null) add("dueDate")
        if (t.isAllDay == null) add("isAllDay")
        if (t.timeZone == null) add("timeZone")
        if (t.isFloating == null) add("isFloating")
        if (t.repeatFlag == null) add("repeatRule")
        if (t.repeatFrom == null) add("repeatFrom")
        if (t.repeatTaskId == null) add("repeatTaskId")
        if (t.sortOrder == null) add("sortOrder")
        if (t.completedTime == null) add("completedAt")
        if (t.createdTime == null) add("createdAt")
        if (t.pinnedTime == null) add("pinnedAt")
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
