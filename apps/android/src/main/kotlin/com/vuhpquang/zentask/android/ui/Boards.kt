package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.TaskIndex
import com.vuhpquang.zentask.tasks.TaskRow
import com.vuhpquang.zentask.tasks.TaskSection
import com.vuhpquang.zentask.tasks.isDone

private data class QuadrantLook(val title: String, val color: Color)

private val QUADRANTS = listOf(
    5 to QuadrantLook("Urgent & important", Color(0xFFE5484D)),
    3 to QuadrantLook("Not urgent & important", Color(0xFFF59E0B)),
    1 to QuadrantLook("Urgent & not important", Color(0xFF3B82F6)),
    0 to QuadrantLook("Not urgent & not important", Color(0xFF10B981)),
)

/** A compact task line for boards: check, title, date; tap opens, long-press offers [menu]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardTaskRow(app: ZenApp, index: TaskIndex?, row: TaskRow, open: (String) -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    val task = row.task
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { open(task.id) }, onLongClick = { menuOpen = true })
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckCircleButton(task) { toggleComplete(app, task) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title.ifEmpty { "Untitled" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null, style = MaterialTheme.typography.bodyMedium)
                val day = index?.let { app.core.taskDayMillis(it, task) }
                if (day != null) {
                    Text(dateText(day, task), style = MaterialTheme.typography.labelSmall,
                        color = if (row.overdue) ZenColors.high else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
    }
}

/** The Eisenhower Matrix: open tasks in four quadrants by priority; long-press moves a task to another one. */
@Composable
fun MatrixBoard(app: ZenApp, index: TaskIndex?, open: (String) -> Unit) {
    val quadrants = remember(index) { index?.let { app.core.matrix(it) }.orEmpty() }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in QUADRANTS.chunked(2)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((priority, look) in pair) {
                    val rows = quadrants.firstOrNull { it.priority == priority }?.sections?.flatMap { it.rows }.orEmpty()
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(look.color.copy(alpha = 0.10f)).padding(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(look.color))
                            Text(" ${look.title}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = look.color,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (rows.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        } else {
                            LazyColumn {
                                items(rows, key = { it.task.id }) { row ->
                                    BoardTaskRow(app, index, row, open) { close ->
                                        for ((p, l) in QUADRANTS) if (p != priority) {
                                            DropdownMenuItem(text = { Text("Move to ${l.title}") }, onClick = { app.core.actions.setPriority(row.task.id, p); close() })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Kanban: one column per list section, add a task at the bottom of a column, long-press to move between sections. */
@Composable
fun KanbanBoard(app: ZenApp, index: TaskIndex?, project: Project, open: (String) -> Unit) {
    val sections = remember(index, project.id) {
        index?.let { app.core.sections(it, TaskFilter.InProject(project.id), app.core.viewOptions(TaskFilter.InProject(project.id))) }.orEmpty()
            .filter { !it.isCompleted }
            // An empty list still gets a column to add the first task to.
            .ifEmpty { listOf(TaskSection(title = "", rows = emptyList(), key = "col:none")) }
    }
    var newSection by remember { mutableStateOf(false) }
    LazyRow(Modifier.fillMaxSize().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
        items(sections, key = { it.key }) { section -> KanbanColumn(app, index, project, section, sections, open) }
        item {
            TextButton(onClick = { newSection = true }) { Icon(Icons.Filled.Add, null); Text(" Add section") }
        }
    }
    if (newSection) NameDialog("New section", "", "Add", close = { newSection = false }) { app.core.listActions.createColumn(project.id, it) }
}

@Composable
private fun KanbanColumn(app: ZenApp, index: TaskIndex?, project: Project, section: TaskSection, all: List<TaskSection>, open: (String) -> Unit) {
    var adding by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(
        Modifier.width(280.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.title.ifEmpty { "Not sectioned" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${section.rows.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            if (section.columnId != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Section options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename section") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete section") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(section.rows, key = { it.task.id }) { row ->
                BoardTaskRow(app, index, row, open) { close ->
                    for (other in all) if (other.key != section.key) {
                        DropdownMenuItem(text = { Text("Move to ${other.title.ifEmpty { "Not sectioned" }}") }, onClick = {
                            app.core.listActions.reorderTask(row.task.id, after = other.rows.lastOrNull()?.task?.id, before = null, columnId = other.columnId, setColumn = true)
                            close()
                        })
                    }
                }
            }
        }
        OutlinedTextField(
            adding, { adding = it }, placeholder = { Text("Add task") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val title = adding.trim()
                if (title.isNotEmpty()) {
                    val t: Task = app.core.actions.create(title, TaskFilter.InProject(project.id))
                    if (section.columnId != null) {
                        app.core.listActions.reorderTask(t.id, after = section.rows.lastOrNull()?.task?.id, before = null, columnId = section.columnId, setColumn = true)
                    }
                }
                adding = ""
            }),
        )
    }
    if (renaming) NameDialog("Rename section", section.title, "Save", close = { renaming = false }) { name -> section.columnId?.let { app.core.listActions.renameColumn(it, name) } }
    if (deleting) {
        Confirm("Delete \"${section.title}\"?", "Its tasks stay in the list, without a section.", "Delete",
            onConfirm = { section.columnId?.let { app.core.listActions.deleteColumn(it) }; deleting = false }, onDismiss = { deleting = false })
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, close: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()); close() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
