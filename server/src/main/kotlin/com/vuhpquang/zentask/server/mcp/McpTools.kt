package com.vuhpquang.zentask.server.mcp

import com.vuhpquang.zentask.model.EntityTypes
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.model.ZenJson
import com.vuhpquang.zentask.model.toFields
import com.vuhpquang.zentask.tasks.QuickAddParser
import com.vuhpquang.zentask.tasks.Recurrence
import com.vuhpquang.zentask.tasks.SmartList
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.isDone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/** A tool's answer: text for the model, an error flag, and the task ids it changed (for the audit). */
class ToolResult(val text: String, val isError: Boolean = false, val changed: List<String> = emptyList())

/** A failure the model should read and correct (bad id, bad date…): becomes `isError: true`, not a protocol error. */
class ToolError(message: String) : Exception(message)

/** One MCP tool: name, description, JSON schema of its arguments, whether it writes, and its handler. */
class Tool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val writes: Boolean,
    val run: (args: JsonObject, ctx: McpReplica.Context, zone: TimeZone) -> ToolResult,
)

/** The ZenTask tools (spec §10a). Deletion moves to Trash unless `permanent` on a task already in Trash. */
object McpTools {
    private const val MAX_TITLE = 500

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                "search_tasks",
                "Find tasks. Defaults to open tasks. Combine filters freely. Returns at most `limit` tasks (default 50).",
                schema(
                    "query" to str("Text to find in titles and notes (case and accent insensitive)"),
                    "smart_list" to enumStr("A built-in list", "today", "tomorrow", "next_7_days", "inbox", "all"),
                    "list" to str("List name or id"),
                    "tag" to str("Tag name"),
                    "status" to enumStr("Which tasks (default open)", "open", "completed", "wont_do", "trashed", "any"),
                    "due_after" to str("ISO date: due on or after"),
                    "due_before" to str("ISO date: due on or before"),
                    "limit" to int("Max results, 1-200"),
                    "timezone" to str("IANA zone for dates, default Asia/Ho_Chi_Minh"),
                ),
                writes = false,
            ) { a, ctx, zone -> searchTasks(a, ctx, zone) },
            Tool(
                "get_task", "One task with notes, checklist and subtasks.",
                schema("id" to str("Task id"), required = listOf("id")), writes = false,
            ) { a, ctx, zone -> ToolResult(json(fullTask(task(ctx, a.req("id")), ctx, zone))) },
            Tool(
                "create_task",
                "Create a task. Either give fields, or `quick_add` text like \"Pay rent tomorrow 9am #home !high ^personal\" (English or Vietnamese).",
                schema(
                    "title" to str("Title"),
                    "quick_add" to str("Quick-add text; parsed like the app's add field"),
                    "list" to str("List name or id (default Inbox)"),
                    "due" to str("ISO date (all-day) or date-time, e.g. 2026-11-09 or 2026-11-09T09:00"),
                    "priority" to enumStr("Priority", "high", "medium", "low", "none"),
                    "tags" to strArray("Tag names"),
                    "notes" to str("Notes (markdown)"),
                    "parent_id" to str("Make it a subtask of this task"),
                    "timezone" to str("IANA zone for dates, default Asia/Ho_Chi_Minh"),
                ),
                writes = true,
            ) { a, ctx, zone -> createTask(a, ctx, zone) },
            Tool(
                "update_task",
                "Change fields of a task. Only the fields you pass change. `due: null` clears the date.",
                schema(
                    "id" to str("Task id"),
                    "title" to str("New title"),
                    "notes" to str("New notes"),
                    "due" to str("ISO date or date-time; null clears"),
                    "priority" to enumStr("Priority", "high", "medium", "low", "none"),
                    "tags" to strArray("Replace all tags"),
                    "add_tags" to strArray("Tags to add"),
                    "remove_tags" to strArray("Tags to remove"),
                    "list" to str("Move to this list (name or id)"),
                    "timezone" to str("IANA zone for dates, default Asia/Ho_Chi_Minh"),
                    required = listOf("id"),
                ),
                writes = true,
            ) { a, ctx, zone -> updateTask(a, ctx, zone) },
            simple("complete_task", "Complete a task. A repeating task keeps a completed copy and moves to its next date.") { ctx, id -> ctx.actions.complete(id) },
            simple("reopen_task", "Mark a completed or won't-do task as open again.") { ctx, id -> ctx.actions.uncomplete(id) },
            simple("wont_do_task", "Mark a task as won't do.") { ctx, id -> ctx.actions.wontDo(id) },
            simple("trash_task", "Move a task (and its subtasks) to Trash. It can be restored.") { ctx, id -> ctx.actions.trash(id) },
            simple("restore_task", "Bring a task back from Trash.") { ctx, id -> ctx.actions.restore(id) },
            Tool(
                "delete_task",
                "Delete a task: moves it to Trash. With permanent=true (only for a task already in Trash) it is deleted for good.",
                schema("id" to str("Task id"), "permanent" to bool("Delete for good (task must be in Trash)"), required = listOf("id")),
                writes = true,
            ) { a, ctx, zone ->
                val t = task(ctx, a.req("id"))
                if (a.bool("permanent") == true) {
                    if (t.trashedAt == null) throw ToolError("The task is not in Trash. Call trash_task first, or delete without permanent.")
                    ctx.actions.deleteForever(t.id)
                    ToolResult("Deleted permanently: ${t.title}", changed = listOf(t.id))
                } else {
                    ctx.actions.trash(t.id)
                    ToolResult("Moved to Trash: " + json(brief(ctx.store.get(EntityTypes.task, t.id)!!, ctx, zone)), changed = listOf(t.id))
                }
            },
            Tool(
                "add_subtask", "Add a subtask under a task.",
                schema("parent_id" to str("Parent task id"), "title" to str("Title"), required = listOf("parent_id", "title")), writes = true,
            ) { a, ctx, zone ->
                val parent = task(ctx, a.req("parent_id"))
                val sub = ctx.actions.addSubtask(parent.id, title(a.req("title")))
                ToolResult(json(brief(sub, ctx, zone)), changed = listOf(sub.id))
            },
            Tool(
                "add_checklist_item", "Add a checklist item to a task (the task becomes a checklist).",
                schema("task_id" to str("Task id"), "title" to str("Item text"), required = listOf("task_id", "title")), writes = true,
            ) { a, ctx, zone ->
                val t = task(ctx, a.req("task_id"))
                val item = ctx.actions.addItem(t.id, title(a.req("title")))
                ToolResult("Added item ${item.id} to ${t.title}", changed = listOf(t.id))
            },
            Tool(
                "set_checklist_item", "Tick, untick or rename a checklist item.",
                schema("item_id" to str("Item id"), "done" to bool("Ticked or not"), "title" to str("New text"), required = listOf("item_id")), writes = true,
            ) { a, ctx, _ ->
                val id = a.req("item_id")
                val item = ctx.store.get(EntityTypes.checklistItem, id)?.takeIf { !it.deleted } ?: throw ToolError("No checklist item $id")
                a.bool("done")?.let { want -> if ((item.status != TaskStatus.OPEN) != want) ctx.actions.toggleItem(id) }
                a.str("title")?.let { ctx.actions.renameItem(id, title(it)) }
                ToolResult("Updated item $id", changed = listOf(item.taskId))
            },
            Tool("list_lists", "All lists with their folder and open-task count.", schema(), writes = false) { _, ctx, zone ->
                val counts = ctx.index.counts(Clock.System.now(), zone)
                val groups = ctx.store.list(EntityTypes.projectGroup).associate { it.id to it.name }
                ToolResult(
                    json(
                        buildJsonArray {
                            ctx.index.projects.filter { it.trashedAt == null && !it.deleted }.forEach { p ->
                                add(
                                    buildJsonObject {
                                        put("id", p.id); put("name", p.name)
                                        p.groupId?.let { put("folder", groups[it]) }
                                        put("open", counts[TaskFilter.InProject(p.id)] ?: 0)
                                        if (p.closed) put("archived", true)
                                    },
                                )
                            }
                        },
                    ),
                )
            },
            Tool("list_tags", "All tags with colour, parent and open-task count.", schema(), writes = false) { _, ctx, zone ->
                val counts = ctx.index.counts(Clock.System.now(), zone)
                ToolResult(
                    json(
                        buildJsonArray {
                            ctx.index.tags.filter { !it.deleted }.forEach { t ->
                                add(
                                    buildJsonObject {
                                        put("name", t.name)
                                        if (t.label.isNotEmpty() && t.label != t.name) put("label", t.label)
                                        t.color?.let { put("color", it) }
                                        t.parent?.let { put("parent", it) }
                                        put("open", counts[TaskFilter.WithTag(t.name)] ?: 0)
                                    },
                                )
                            }
                        },
                    ),
                )
            },
            Tool(
                "rename_tag", "Rename a tag on every task (merges into an existing tag of that name).",
                schema("name" to str("Current name"), "new_name" to str("New name"), required = listOf("name", "new_name")), writes = true,
            ) { a, ctx, _ ->
                val affected = tagged(ctx, a.req("name"))
                ctx.tags.rename(a.req("name"), a.req("new_name"))
                ToolResult("Renamed #${a.req("name")} to #${a.req("new_name").lowercase()} on ${affected.size} tasks", changed = affected)
            },
            Tool(
                "set_tag_color", "Set a tag's colour (#RRGGBB).",
                schema("name" to str("Tag"), "color" to str("#RRGGBB"), required = listOf("name", "color")), writes = true,
            ) { a, ctx, _ ->
                val color = a.req("color")
                if (!Regex("^#[0-9A-Fa-f]{6}$").matches(color)) throw ToolError("Colour must look like #2E9E88")
                ctx.tags.setColor(a.req("name"), color)
                ToolResult("Colour of #${a.req("name")} set to $color")
            },
            Tool(
                "delete_tag", "Remove a tag from every task and delete it. No task is deleted.",
                schema("name" to str("Tag"), required = listOf("name")), writes = true,
            ) { a, ctx, _ ->
                val affected = tagged(ctx, a.req("name"))
                ctx.tags.delete(a.req("name"))
                ToolResult("Deleted #${a.req("name")} (removed from ${affected.size} tasks)", changed = affected)
            },
        )
    }

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    // ---- handlers ----

    private fun simple(name: String, description: String, action: (McpReplica.Context, String) -> Unit) = Tool(
        name, description, schema("id" to str("Task id"), required = listOf("id")), writes = true,
    ) { a, ctx, zone ->
        val t = task(ctx, a.req("id"))
        fun copies() = ctx.store.list(EntityTypes.task).filter { it.repeatTaskId == t.id }.map { it.id }.toSet()
        val before = copies()
        action(ctx, t.id)
        val changed = listOf(t.id) + (copies() - before) // a repeating task's new completed copy
        ToolResult(json(brief(ctx.store.get(EntityTypes.task, t.id)!!, ctx, zone)), changed = changed)
    }

    private fun searchTasks(a: JsonObject, ctx: McpReplica.Context, zone: TimeZone): ToolResult {
        val now = Clock.System.now()
        val limit = (a.int("limit") ?: 50).coerceIn(1, 200)
        val status = a.str("status") ?: "open"
        var tasks: List<Task> = when (val smart = a.str("smart_list")) {
            null -> ctx.index.allTasks
            else -> {
                val list = when (smart) {
                    "today" -> SmartList.TODAY
                    "tomorrow" -> SmartList.TOMORROW
                    "next_7_days" -> SmartList.NEXT_7_DAYS
                    "inbox" -> SmartList.INBOX
                    "all" -> SmartList.ALL
                    else -> throw ToolError("Unknown smart_list $smart")
                }
                ctx.index.rows(TaskFilter.Smart(list), now, zone).flatMap { listOf(it.task) + it.subtasks }
            }
        }
        tasks = tasks.filter {
            when (status) {
                "open" -> it.status == TaskStatus.OPEN && it.trashedAt == null
                "completed" -> it.isDone && it.trashedAt == null
                "wont_do" -> it.status == TaskStatus.WONT_DO && it.trashedAt == null
                "trashed" -> it.trashedAt != null
                "any" -> true
                else -> throw ToolError("Unknown status $status")
            }
        }
        a.str("list")?.let { name -> val p = project(ctx, name); tasks = tasks.filter { it.projectId == p.id } }
        a.str("tag")?.let { tag -> val k = tag.removePrefix("#").lowercase(); tasks = tasks.filter { t -> t.tags.any { it.lowercase() == k } } }
        a.str("query")?.let { q ->
            val k = QuickAddParser.fold(q.lowercase())
            tasks = tasks.filter { QuickAddParser.fold((it.title + "\n" + it.content + "\n" + it.desc).lowercase()).contains(k) }
        }
        a.str("due_after")?.let { d -> val from = date(d, zone).first; tasks = tasks.filter { (it.dueDate ?: it.startDate ?: Long.MIN_VALUE) >= from } }
        a.str("due_before")?.let { d -> val until = endOfDay(d, zone); tasks = tasks.filter { (it.dueDate ?: it.startDate ?: Long.MAX_VALUE) <= until } }
        val shown = tasks.take(limit)
        return ToolResult(
            json(
                buildJsonObject {
                    put("count", tasks.size)
                    if (tasks.size > shown.size) put("shown", shown.size)
                    put("tasks", JsonArray(shown.map { brief(it, ctx, zone) }))
                },
            ),
        )
    }

    private fun createTask(a: JsonObject, ctx: McpReplica.Context, zone: TimeZone): ToolResult {
        val quick = a.str("quick_add")?.let { QuickAddParser.parse(it, Clock.System.now(), zone, ctx.index.projects) }
        val name = a.str("title") ?: quick?.title ?: throw ToolError("Give a title or quick_add text")
        val parent = a.str("parent_id")?.let { task(ctx, it) }
        if (parent != null) {
            val sub = ctx.actions.addSubtask(parent.id, title(name))
            return ToolResult(json(brief(sub, ctx, zone)), changed = listOf(sub.id))
        }
        val due = a.str("due")?.let { date(it, zone) }
        val created = ctx.actions.create(
            title = title(name),
            filter = null,
            projectId = a.str("list")?.let { project(ctx, it).id } ?: quick?.projectId,
            priority = a.str("priority")?.let(::priority) ?: quick?.priority,
            tags = a.strings("tags") + quick?.tags.orEmpty(),
            due = due?.first ?: quick?.due,
            allDay = due?.second ?: quick?.allDay ?: true,
        )
        a.str("notes")?.let { ctx.actions.setContent(created.id, it) }
        return ToolResult(json(brief(ctx.store.get(EntityTypes.task, created.id)!!, ctx, zone)), changed = listOf(created.id))
    }

    private fun updateTask(a: JsonObject, ctx: McpReplica.Context, zone: TimeZone): ToolResult {
        val t = task(ctx, a.req("id"))
        a.str("title")?.let { ctx.actions.rename(t.id, title(it)) }
        a.str("notes")?.let { ctx.actions.setContent(t.id, it) }
        if (a.containsKey("due")) {
            val due = a.str("due")?.let { date(it, zone) }
            ctx.actions.setDue(t.id, due?.first, due?.second ?: false)
        }
        a.str("priority")?.let { ctx.actions.setPriority(t.id, priority(it)) }
        val tags = when {
            a.containsKey("tags") -> a.strings("tags")
            else -> t.tags + a.strings("add_tags")
        }.filterNot { tag -> a.strings("remove_tags").any { it.removePrefix("#").equals(tag.removePrefix("#"), ignoreCase = true) } }
        if (tags != t.tags) ctx.actions.setTags(t.id, tags)
        a.str("list")?.let { ctx.actions.move(t.id, project(ctx, it).id) }
        return ToolResult(json(brief(ctx.store.get(EntityTypes.task, t.id)!!, ctx, zone)), changed = listOf(t.id))
    }

    // ---- lookups and parsing ----

    private fun task(ctx: McpReplica.Context, id: String): Task =
        ctx.store.get(EntityTypes.task, id)?.takeIf { !it.deleted } ?: throw ToolError("No task with id $id. Use search_tasks to find ids.")

    private fun project(ctx: McpReplica.Context, nameOrId: String): Project {
        val live = ctx.index.projects.filter { it.trashedAt == null && !it.deleted }
        live.firstOrNull { it.id == nameOrId }?.let { return it }
        val key = QuickAddParser.fold(nameOrId.lowercase()).trim()
        if (key == "inbox") return live.firstOrNull { it.id == Project.INBOX_ID } ?: Project(id = Project.INBOX_ID, name = "Inbox", isInbox = true)
        fun norm(p: Project) = QuickAddParser.fold(p.name.lowercase()).dropWhile { !it.isLetterOrDigit() }.trim()
        return live.firstOrNull { norm(it) == key } ?: live.firstOrNull { norm(it).startsWith(key) }
            ?: throw ToolError("No list named $nameOrId. Use list_lists to see the lists.")
    }

    private fun tagged(ctx: McpReplica.Context, name: String): List<String> {
        val k = name.removePrefix("#").lowercase()
        return ctx.store.list(EntityTypes.task).filter { t -> t.tags.any { it.lowercase() == k } }.map { it.id }
    }

    private fun title(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) throw ToolError("The title is empty")
        if (t.length > MAX_TITLE) throw ToolError("The title is longer than $MAX_TITLE characters")
        return t
    }

    private fun priority(s: String): Int = when (s.lowercase()) {
        "high", "5" -> 5
        "medium", "3" -> 3
        "low", "1" -> 1
        "none", "0" -> 0
        else -> throw ToolError("Priority must be high, medium, low or none")
    }

    /** ISO date → (start-of-day millis, allDay=true); date-time → (millis, false). */
    internal fun date(s: String, zone: TimeZone): Pair<Long, Boolean> {
        val v = s.trim()
        runCatching { return LocalDate.parse(v).atStartOfDayIn(zone).toEpochMilliseconds() to true }
        runCatching { return LocalDateTime.parse(v).toInstant(zone).toEpochMilliseconds() to false }
        runCatching { return Instant.parse(v).toEpochMilliseconds() to false }
        throw ToolError("Can't read the date \"$s\". Use 2026-11-09 or 2026-11-09T09:00")
    }

    private fun endOfDay(s: String, zone: TimeZone): Long {
        val (ms, allDay) = date(s, zone)
        return if (allDay) ms + 86_400_000L - 1 else ms
    }

    // ---- output ----

    private fun brief(t: Task, ctx: McpReplica.Context, zone: TimeZone): JsonObject = buildJsonObject {
        put("id", t.id)
        put("title", t.title)
        put("list", ctx.index.projects.firstOrNull { it.id == t.projectId }?.name ?: if (t.projectId == Project.INBOX_ID) "Inbox" else t.projectId)
        put("status", when { t.isDone -> "completed"; t.status == TaskStatus.WONT_DO -> "wont_do"; else -> "open" })
        put("priority", when (t.priority) { 5 -> "high"; 3 -> "medium"; 1 -> "low"; else -> "none" })
        (t.startDate ?: t.dueDate)?.let { put("due", formatDue(it, t, zone)) }
        if (t.tags.isNotEmpty()) putJsonArray("tags") { t.tags.forEach { add(JsonPrimitive(it)) } }
        t.repeatRule?.let { put("repeat", Recurrence.describe(it)) }
        t.parentId?.let { put("parent_id", it) }
        if (t.trashedAt != null) put("trashed", true)
    }

    private fun fullTask(t: Task, ctx: McpReplica.Context, zone: TimeZone): JsonObject {
        val base = brief(t, ctx, zone)
        return JsonObject(
            base + buildJsonObject {
                if (t.content.isNotEmpty() || t.desc.isNotEmpty()) put("notes", t.content.ifEmpty { t.desc })
                val items = ctx.index.items(t.id)
                if (items.isNotEmpty()) putJsonArray("checklist") {
                    items.forEach { i -> add(buildJsonObject { put("id", i.id); put("title", i.title); put("done", i.status != TaskStatus.OPEN) }) }
                }
                val subs = ctx.index.subtasks(t.id)
                if (subs.isNotEmpty()) putJsonArray("subtasks") { subs.forEach { add(brief(it, ctx, zone)) } }
                t.completedAt?.let { put("completed_at", Instant.fromEpochMilliseconds(it).toString()) }
            },
        )
    }

    private fun formatDue(ms: Long, t: Task, zone: TimeZone): String {
        val z = if (t.isAllDay) t.timeZone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: zone else zone
        val local = Instant.fromEpochMilliseconds(ms).toLocalDateTime(z)
        return if (t.isAllDay) local.date.toString() else local.toString().take(16)
    }

    internal fun json(e: JsonElement): String = ZenJson.encodeToString(JsonElement.serializer(), e)

    /** Before/after audit payload for changed tasks. */
    internal fun snapshot(ctx: McpReplica.Context, ids: List<String>): JsonObject = buildJsonObject {
        ids.forEach { id -> put(id, ctx.store.get(EntityTypes.task, id)?.let { JsonObject(EntityTypes.task.toFields(it)) } ?: JsonNull) }
    }

    // ---- schema helpers ----

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private fun str(d: String) = buildJsonObject { put("type", "string"); put("description", d) }
    private fun int(d: String) = buildJsonObject { put("type", "integer"); put("description", d) }
    private fun bool(d: String) = buildJsonObject { put("type", "boolean"); put("description", d) }
    private fun strArray(d: String) = buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", d) }
    private fun enumStr(d: String, vararg values: String) = buildJsonObject {
        put("type", "string"); put("description", d); putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    // ---- argument helpers ----

    private fun JsonObject.str(k: String): String? = when (val v = this[k]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw ToolError("$k must be a string")
        else -> throw ToolError("$k must be a string")
    }

    private fun JsonObject.req(k: String): String = str(k)?.takeIf { it.isNotBlank() } ?: throw ToolError("Missing $k")

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: throw ToolError("$k must be a number") }

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: throw ToolError("$k must be true or false") }

    private fun JsonObject.strings(k: String): List<String> = when (val v = this[k]) {
        null, is JsonNull -> emptyList()
        is JsonArray -> v.map { (it as? JsonPrimitive)?.contentOrNull ?: throw ToolError("$k must be a list of strings") }
        else -> throw ToolError("$k must be a list of strings")
    }
}
