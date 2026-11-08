package com.vuhpquang.zentask.ticktick

/**
 * TickTick's CSV backup → [TickTickSnapshot], for a first import when there is no cookie (spec §10.2).
 * The backup has no stable ids (its taskId column is a per-export sequence), so ids are derived from
 * content: `csv_<hash(list|title|createdTime)>`. They are stable across exports of unchanged tasks,
 * but never match the real TickTick ids an API sync uses — hence CSV import only before any API sync.
 */
object TickTickCsv {
    private const val INBOX_LIST = "Inbox"
    private const val CSV_INBOX = "csv_inbox"
    private const val DEFAULT_COLUMN = "Not Sectioned"
    private val LINE_BREAK = Regex("\r\n|\r|\n")
    private val TRIGGER = Regex("""-?P(?:\d+W)?(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+S)?)?""")

    fun parse(text: String): TickTickSnapshot {
        val body = text.removePrefix("﻿")
        val start = body.indexOf("\"Folder Name\"").takeIf { it >= 0 } ?: body.indexOf("Folder Name,").takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Not a TickTick backup: no \"Folder Name\" header")
        val rows = parseCsv(body.substring(start))
        val header = rows.first()
        val records = rows.drop(1).filter { r -> r.any { it.isNotEmpty() } }.map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" } } }

        val groups = LinkedHashMap<String, TtGroup>()
        val projects = LinkedHashMap<String, TtProject>()
        val columns = LinkedHashMap<String, TtColumn>()
        fun projectId(list: String) = if (list == INBOX_LIST) CSV_INBOX else "csv_" + hash("list|$list")

        records.forEach { r ->
            val list = r["List Name"].orEmpty()
            val folder = r["Folder Name"].orEmpty()
            val groupId = folder.takeIf { it.isNotEmpty() }?.let { f ->
                groups.getOrPut(f) { TtGroup(id = "csv_" + hash("folder|$f"), name = f, sortOrder = groups.size.toLong()) }.id
            }
            if (list != INBOX_LIST) {
                projects.getOrPut(list) {
                    TtProject(
                        id = projectId(list), name = list, groupId = groupId, kind = r["projectKind"]?.ifEmpty { null },
                        viewMode = r["View Mode"]?.ifEmpty { null }, sortOrder = projects.size.toLong(),
                    )
                }
            }
            val column = r["Column Name"].orEmpty()
            if (column.isNotEmpty() && column != DEFAULT_COLUMN) {
                columns.getOrPut("$list|$column") {
                    TtColumn(id = "csv_" + hash("column|$list|$column"), projectId = projectId(list), name = column, sortOrder = r["Column Order"]?.toLongOrNull())
                }
            }
        }

        // Content-derived ids; a collision (same list, title and creation time) gets a #n suffix in file order.
        val seen = HashMap<String, Int>()
        val idBySeq = HashMap<String, String>()
        val ids = records.map { r ->
            val base = "csv_" + hash("task|${r["List Name"]}|${r["Title"]}|${r["Created Time"]}")
            val n = seen.getOrElse(base) { 0 }
            seen[base] = n + 1
            (if (n == 0) base else "$base#$n").also { id -> r["taskId"]?.takeIf { it.isNotEmpty() }?.let { idBySeq[it] = id } }
        }

        val tasks = records.mapIndexed { i, r ->
            val id = ids[i]
            val list = r["List Name"].orEmpty()
            val checklist = r["Kind"] == "CHECKLIST" || r["Is Check list"] == "Y"
            val (desc, items) = if (checklist) checklist(id, r["Content"].orEmpty()) else "" to emptyList()
            val column = r["Column Name"].orEmpty()
            TtTask(
                id = id,
                projectId = projectId(list),
                parentId = r["parentId"]?.takeIf { it.isNotEmpty() }?.let { idBySeq[it] },
                columnId = columns["$list|$column"]?.id,
                title = r["Title"],
                content = if (checklist) "" else r["Content"]?.replace(LINE_BREAK, "\n"),
                desc = desc,
                kind = if (checklist) "CHECKLIST" else r["Kind"]?.ifEmpty { null },
                priority = r["Priority"]?.toIntOrNull(),
                status = r["Status"]?.toIntOrNull(),
                startDate = r["Start Date"],
                dueDate = r["Due Date"],
                isAllDay = r["Is All Day"] == "true",
                timeZone = r["Timezone"]?.ifEmpty { null },
                isFloating = r["Is Floating"] == "true",
                repeatFlag = r["Repeat"],
                reminders = TRIGGER.findAll(r["Reminder"].orEmpty()).map { TtReminder(it.value) }.filter { it.trigger!!.length > 1 }.toList(),
                tags = r["Tags"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                items = items,
                sortOrder = r["Order"]?.toLongOrNull(),
                completedTime = r["Completed Time"],
                createdTime = r["Created Time"],
            )
        }
        return TickTickSnapshot(
            inboxId = CSV_INBOX,
            groups = groups.values.toList(),
            projects = projects.values.toList(),
            columns = columns.values.toList(),
            tasks = tasks,
            wontDoAvailable = true, // the backup has every status
            columnsAvailable = true,
        )
    }

    /** `▫` open / `▪` done lines become items; other lines stay as the description. */
    private fun checklist(taskId: String, content: String): Pair<String, List<TtItem>> {
        val items = mutableListOf<TtItem>()
        val desc = mutableListOf<String>()
        content.split(LINE_BREAK).forEach { line ->
            val open = line.startsWith("▫")
            if (open || line.startsWith("▪")) {
                val title = line.trimStart('▫', '▪', ' ', '\t').trim()
                if (title.isNotEmpty()) {
                    items += TtItem(id = "${taskId}_${items.size}", title = title, status = if (open) 0 else 1, sortOrder = items.size.toLong())
                }
            } else if (line.isNotBlank()) {
                desc += line
            }
        }
        return desc.joinToString("\n") to items
    }

    /** RFC 4180: quoted fields may hold commas, newlines and doubled quotes. */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    /** FNV-1a 64-bit, hex: a stable content key (not security-relevant). */
    internal fun hash(s: String): String {
        var h = 0xcbf29ce484222325uL
        for (b in s.encodeToByteArray()) {
            h = h xor b.toUByte().toULong()
            h *= 0x100000001b3uL
        }
        return h.toString(16).padStart(16, '0')
    }
}
