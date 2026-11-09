package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.background
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
