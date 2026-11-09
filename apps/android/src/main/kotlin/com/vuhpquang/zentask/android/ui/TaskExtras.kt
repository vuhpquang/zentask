package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.tasks.TaskIndex
import com.vuhpquang.zentask.tasks.isDone
import kotlinx.coroutines.launch

/**
 * A task row you can swipe: right completes (or reopens), left moves it to Trash ([trashed] offers Undo:
 * the row leaves the list, so the snackbar must live in the screen's scope, not the row's).
 * The row never stays dismissed itself: the list updates from the data.
 */
@Composable
fun SwipeTaskRow(app: ZenApp, task: Task, enabled: Boolean, trashed: (Task) -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> toggleComplete(app, task)
            SwipeToDismissBoxValue.EndToStart -> {
                app.core.actions.trash(task.id)
                trashed(task)
            }
            SwipeToDismissBoxValue.Settled -> {}
        }
        false
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (toEnd) ZenColors.done else ZenColors.high
            Box(
                Modifier.fillMaxSize().background(color.copy(alpha = 0.85f)).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    Icon(if (toEnd) Icons.Filled.Check else Icons.Filled.Delete, if (toEnd) "Complete" else "Move to Trash", tint = Color.White)
                }
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

/** "Moved to Trash" with Undo, in the screen's [scope]. */
fun offerUndoTrash(app: ZenApp, task: Task, snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar("Moved \"${task.title.ifBlank { "Untitled" }}\" to Trash", actionLabel = "Undo", withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) app.core.actions.restore(task.id)
    }
}

/** The task's tags as removable chips, plus "Add tag". */
@Composable
fun TagChips(app: ZenApp, index: TaskIndex?, task: Task) {
    var editing by remember { mutableStateOf(false) }
    for (tag in task.tags) {
        InputChip(
            selected = false,
            onClick = { editing = true },
            label = { Text("#$tag") },
            trailingIcon = {
                Icon(Icons.Filled.Close, "Remove $tag", Modifier.size(18.dp).clickable { app.core.actions.setTags(task.id, task.tags - tag) })
            },
        )
    }
    AssistChip(onClick = { editing = true }, label = { Text(if (task.tags.isEmpty()) "Add tag" else "Tags") }, leadingIcon = { Icon(Icons.Filled.Tag, null) })
    if (editing) TagDialog(index, task.tags, close = { editing = false }) { app.core.actions.setTags(task.id, it) }
}

@Composable
private fun TagDialog(index: TaskIndex?, current: List<String>, close: () -> Unit, save: (List<String>) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var typed by remember { mutableStateOf("") }
    val all = (index?.tags.orEmpty().filter { !it.deleted }.map { it.name } + current).distinct().sortedBy { it.lowercase() }
    fun addTyped() {
        val name = typed.trim().removePrefix("#").trim()
        if (name.isNotEmpty() && name !in chosen) chosen.add(name)
        typed = ""
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(typed, { typed = it }, label = { Text("New tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = ::addTyped, enabled = typed.isNotBlank()) { Text("Add") } })
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items((all + chosen).distinct()) { name ->
                        Row(Modifier.fillMaxWidth().clickable { if (name in chosen) chosen.remove(name) else chosen.add(name) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = name in chosen, onCheckedChange = { on -> if (on) chosen.add(name) else chosen.remove(name) })
                            Text("#$name")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addTyped(); save(chosen.toList()); close() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/** "…" on a task: pin, duplicate, Won't do, Trash; Restore / Delete forever when it's in Trash. */
@Composable
fun TaskMenu(app: ZenApp, task: Task, close: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (task.trashedAt != null) {
                DropdownMenuItem(text = { Text("Restore") }, leadingIcon = { Icon(Icons.Filled.Restore, null) },
                    onClick = { app.core.actions.restore(task.id); open = false })
                DropdownMenuItem(text = { Text("Delete forever…") }, leadingIcon = { Icon(Icons.Filled.DeleteForever, null) },
                    onClick = { confirmDelete = true; open = false })
            } else {
                DropdownMenuItem(text = { Text(if (task.pinnedAt != null) "Unpin" else "Pin to top") }, leadingIcon = { Icon(Icons.Filled.PushPin, null) }, onClick = {
                    if (task.pinnedAt != null) app.core.actions.unpin(task.id) else app.core.actions.pin(task.id)
                    open = false
                })
                if (!task.isDone && task.status != TaskStatus.WONT_DO) {
                    DropdownMenuItem(text = { Text("Start focus") }, leadingIcon = { Icon(Icons.Filled.Timer, null) },
                        onClick = { open = false; app.focusOn(task.id) })
                }
                DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { app.core.actions.duplicate(task.id); open = false })
                val wontDo = task.status == TaskStatus.WONT_DO
                DropdownMenuItem(text = { Text(if (wontDo) "Reopen" else "Won't do") }, leadingIcon = { Icon(Icons.Filled.Block, null) }, onClick = {
                    if (wontDo || task.isDone) app.core.actions.uncomplete(task.id) else app.core.actions.wontDo(task.id)
                    open = false
                })
                DropdownMenuItem(text = { Text("Move to Trash") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { app.core.actions.trash(task.id); open = false; close() })
            }
        }
    }
    if (confirmDelete) {
        Confirm(
            "Delete \"${task.title.ifBlank { "Untitled" }}\" forever?",
            "The task, its subtasks in Trash and its checklist are deleted on every device. This can't be undone.",
            "Delete forever",
            onConfirm = { runCatching { app.core.actions.deleteForever(task.id) }; confirmDelete = false; close() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "Focus on": the task the next session is saved with. */
@Composable
fun FocusTaskPicker(app: ZenApp) {
    val taskId by app.focusTaskId.collectAsState()
    val index by app.index.collectAsState()
    var open by remember { mutableStateOf(false) }
    val task = taskId?.let { index?.task(it) }
    Box(Modifier.padding(bottom = 8.dp)) {
        AssistChip(onClick = { open = true }, label = { Text(task?.title?.ifBlank { "Untitled" } ?: "Focus on a task…", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Check, null) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No task") }, onClick = { app.setFocusTask(null); open = false })
            val today = index?.let { app.core.sections(it, com.vuhpquang.zentask.tasks.TaskFilter.Smart(com.vuhpquang.zentask.tasks.SmartList.NEXT_7_DAYS), app.core.viewOptions(com.vuhpquang.zentask.tasks.TaskFilter.Smart(com.vuhpquang.zentask.tasks.SmartList.NEXT_7_DAYS))) }
                .orEmpty().flatMap { it.rows }.map { it.task }.filter { it.status == TaskStatus.OPEN }.take(30)
            for (t in today) DropdownMenuItem(text = { Text(t.title.ifBlank { "Untitled" }, maxLines = 1) }, onClick = { app.setFocusTask(t.id); open = false })
        }
    }
}

/** One finished session: time, length, its task; long-press to delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusRecordRow(app: ZenApp, r: com.vuhpquang.zentask.model.FocusRecord) {
    val index by app.index.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    fun t(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(fmt)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = { r.taskId?.let { app.openTask.value = it } }, onLongClick = { confirm = true })
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (r.kind == com.vuhpquang.zentask.model.FocusRecord.KIND_STOPWATCH) Icons.Filled.Timer else Icons.Filled.Check, null,
            tint = ZenColors.violet, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("${t(r.start)} – ${t(r.end)}")
            r.taskId?.let { id -> index?.task(id)?.let { Text(it.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } }
        }
        Text("${r.minutes}m", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirm) {
        Confirm("Delete this session?", "It's removed from your focus stats on every device.", "Delete",
            onConfirm = { app.core.focusActions.delete(r.id); confirm = false }, onDismiss = { confirm = false })
    }
}

/** "Group and sort" for the list being viewed (synced per list, the same choices as the Mac's ⇅ menu). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SortSheet(app: ZenApp, pick: ListPick, close: () -> Unit) {
    val filter = pick.filter
    var o by remember { mutableStateOf(app.core.viewOptions(filter)) }
    fun set(n: com.vuhpquang.zentask.lists.ViewOptions) { o = n; app.core.setViewOptions(filter, n) }
    val groups = buildList {
        addAll(listOf("" to "Default", "list" to "List", "date" to "Date", "priority" to "Priority", "tag" to "Tag"))
        if (pick is ListPick.InProject) add("column" to "Section")
        add("none" to "None")
    }
    val sorts = listOf("" to "Default", "date" to "Date", "priority" to "Priority", "title" to "Title", "manual" to "Manual")
    val orders = when (if (pick is ListPick.InProject && o.sort.isEmpty()) "manual" else o.sort) {
        "priority" -> listOf("" to "Highest first", "desc" to "Lowest first")
        "date", "" -> listOf("" to "Earliest first", "desc" to "Latest first")
        "title" -> listOf("" to "A to Z", "desc" to "Z to A")
        else -> listOf("" to "Normal", "desc" to "Reversed")
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = close, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Group and sort", style = MaterialTheme.typography.titleLarge)
            for ((title, all, current, change) in listOf(
                Quad("Group by", groups, o.group) { v: String -> set(o.copy(group = v)) },
                Quad("Sort by", sorts, o.sort) { v: String -> set(o.copy(sort = v)) },
                Quad("Order", orders, o.order) { v: String -> set(o.copy(order = v)) },
            )) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in all) androidx.compose.material3.FilterChip(selected = current == value, onClick = { change(value) }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Hide completed", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = o.hideCompleted, onCheckedChange = { set(o.copy(hideCompleted = it)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Hide notes preview", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = o.hideDetails, onCheckedChange = { set(o.copy(hideDetails = it)) })
            }
        }
    }
}

private data class Quad(val title: String, val all: List<Pair<String, String>>, val current: String, val change: (String) -> Unit)
