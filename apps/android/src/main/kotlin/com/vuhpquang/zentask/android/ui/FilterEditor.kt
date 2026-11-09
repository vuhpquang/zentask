package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.lists.FilterRule
import com.vuhpquang.zentask.model.Filter
import com.vuhpquang.zentask.tasks.TaskIndex

private val DUE = listOf(null to "Any date", "today" to "Today", "tomorrow" to "Tomorrow", "next7" to "Next 7 days", "thisweek" to "This week",
    "nextweek" to "Next week", "thismonth" to "This month", "overdue" to "Overdue", "nodate" to "No date")
private val PRIORITIES = listOf(5 to "High", 3 to "Medium", 1 to "Low", 0 to "None")

/** Create or edit a custom smart list (the same rule format as the Mac's filter editor; synced). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterEditorDialog(app: ZenApp, index: TaskIndex?, editing: Filter?, close: () -> Unit, saved: (Filter) -> Unit = {}) {
    val rule = editing?.let { FilterRule.parse(it.rule) } ?: FilterRule()
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    val lists = remember { mutableStateListOf<String>().apply { addAll(rule.lists) } }
    val tags = remember { mutableStateListOf<String>().apply { addAll(rule.tags) } }
    val priorities = remember { mutableStateListOf<Int>().apply { addAll(rule.priorities) } }
    var due by remember { mutableStateOf(rule.due) }
    var keyword by remember { mutableStateOf(rule.keyword.orEmpty()) }
    var any by remember { mutableStateOf(rule.match == "any") }
    fun <T> MutableList<T>.toggle(v: T) { if (v in this) remove(v) else add(v) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (editing == null) "New smart list" else "Edit smart list") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Section("Lists")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = "inbox" in lists, onClick = { lists.toggle("inbox") }, label = { Text("Inbox") })
                    for (p in liveLists(index)) FilterChip(selected = p.id in lists, onClick = { lists.toggle(p.id) }, label = { Text(p.name) })
                }
                val allTags = index?.tags.orEmpty().filter { !it.deleted }
                if (allTags.isNotEmpty()) {
                    Section("Tags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in allTags) FilterChip(selected = t.name in tags, onClick = { tags.toggle(t.name) }, label = { Text("#" + t.name) })
                    }
                }
                Section("Priority")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((p, label) in PRIORITIES) FilterChip(selected = p in priorities, onClick = { priorities.toggle(p) }, label = { Text(label) })
                }
                Section("Date")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((v, label) in DUE) FilterChip(selected = due == v, onClick = { due = v }, label = { Text(label) })
                }
                OutlinedTextField(keyword, { keyword = it }, label = { Text("Title or notes contain") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Section("Match")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !any, onClick = { any = false }, label = { Text("All of these") })
                    FilterChip(selected = any, onClick = { any = true }, label = { Text("Any of these") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val r = rule.copy(
                    lists = lists.toList(), tags = tags.toList(), priorities = priorities.toList(), due = due,
                    keyword = keyword.trim().ifEmpty { null }, match = if (any) "any" else "all",
                )
                saved(app.core.saveFilter(editing?.id, name, r, editing?.icon))
                close()
            }) { Text(if (editing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.labelLarge)

/** "…" for a custom smart list: edit or delete it. */
@Composable
fun FilterMenu(app: ZenApp, index: TaskIndex?, filter: Filter, left: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Smart list options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit smart list") }, onClick = { open = false; editing = true })
            DropdownMenuItem(text = { Text("Delete smart list") }, onClick = { open = false; deleting = true })
        }
    }
    if (editing) FilterEditorDialog(app, index, filter, close = { editing = false })
    if (deleting) {
        Confirm("Delete \"${filter.name}\"?", "Only the smart list goes; its tasks stay where they are.", "Delete",
            onConfirm = { app.core.deleteFilter(filter.id); deleting = false; left() }, onDismiss = { deleting = false })
    }
}
