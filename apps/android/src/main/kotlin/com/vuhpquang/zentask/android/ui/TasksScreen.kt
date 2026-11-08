@file:OptIn(ExperimentalMaterial3Api::class)

package com.vuhpquang.zentask.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.model.Project
import com.vuhpquang.zentask.model.Task
import com.vuhpquang.zentask.model.TaskStatus
import com.vuhpquang.zentask.tasks.SmartList
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.TaskIndex
import com.vuhpquang.zentask.tasks.TaskRow
import com.vuhpquang.zentask.tasks.TaskSection
import com.vuhpquang.zentask.tasks.isDone
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the task list shows (saved across rotation as a string). */
sealed interface ListPick {
    data class Smart(val list: SmartList) : ListPick
    data class InProject(val id: String) : ListPick
    data class WithTag(val name: String) : ListPick
    data class Custom(val id: String) : ListPick

    val filter: TaskFilter
        get() = when (this) {
            is Smart -> TaskFilter.Smart(list)
            is InProject -> TaskFilter.InProject(id)
            is WithTag -> TaskFilter.WithTag(name)
            is Custom -> TaskFilter.Custom(id)
        }

    val key: String
        get() = when (this) {
            is Smart -> "smart:${list.name}"
            is InProject -> "project:$id"
            is WithTag -> "tag:$name"
            is Custom -> "custom:$id"
        }

    companion object {
        fun parse(key: String): ListPick {
            val (kind, value) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return when (kind) {
                "project" -> InProject(value)
                "tag" -> WithTag(value)
                "custom" -> Custom(value)
                else -> Smart(SmartList.entries.firstOrNull { it.name == value } ?: SmartList.TODAY)
            }
        }
    }
}

fun smartTitle(list: SmartList) = when (list) {
    SmartList.ALL -> "All"
    SmartList.TODAY -> "Today"
    SmartList.TOMORROW -> "Tomorrow"
    SmartList.NEXT_7_DAYS -> "Next 7 days"
    SmartList.INBOX -> "Inbox"
    SmartList.COMPLETED -> "Completed"
    SmartList.WONT_DO -> "Won't do"
    SmartList.TRASH -> "Trash"
}

fun smartIcon(list: SmartList): ImageVector = when (list) {
    SmartList.ALL -> Icons.Filled.ViewAgenda
    SmartList.TODAY -> Icons.Filled.WbSunny
    SmartList.TOMORROW -> Icons.Filled.Today
    SmartList.NEXT_7_DAYS -> Icons.Filled.CalendarMonth
    SmartList.INBOX -> Icons.Filled.Inbox
    SmartList.COMPLETED -> Icons.Filled.CheckCircle
    SmartList.WONT_DO -> Icons.Outlined.Circle
    SmartList.TRASH -> Icons.Filled.Delete
}

/** "Today 10:30", "Tomorrow", "Mon, 30 Oct" — the day of a task, in the phone's zone. */
fun dateText(dayMillis: Long, task: Task): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(dayMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val label = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE, d MMM" else "d MMM yyyy"))
    }
    val at = task.startDate ?: task.dueDate
    if (task.isAllDay || at == null) return label
    return label + " " + Instant.ofEpochMilli(at).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}

fun listName(index: TaskIndex?, id: String): String = index?.projects?.firstOrNull { it.id == id }?.name ?: "Inbox"

fun liveLists(index: TaskIndex?): List<Project> =
    index?.projects.orEmpty().filter { !it.isInbox && !it.closed && it.trashedAt == null && !it.deleted }.sortedBy { it.sortOrder }

@Composable
fun TasksScreen(app: ZenApp, openSettings: () -> Unit, openSearch: () -> Unit) {
    val index by app.index.collectAsState()
    var pickKey by rememberSaveable { mutableStateOf("smart:TODAY") }
    val pick = ListPick.parse(pickKey)
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    openTaskId?.let { id ->
        TaskDetailScreen(app, id) { openTaskId = null }
        return
    }

    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ListDrawer(app, index, pick, onPick = { pickKey = it.key; scope.launch { drawer.close() } }, openSettings = {
                scope.launch { drawer.close() }
                openSettings()
            })
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title(index, pick), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Lists") } },
                    actions = { IconButton(onClick = openSearch) { Icon(Icons.Filled.Search, "Search") } },
                )
            },
            floatingActionButton = {
                if (pick !is ListPick.Smart || pick.list !in setOf(SmartList.COMPLETED, SmartList.WONT_DO, SmartList.TRASH)) {
                    FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add task") }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                TaskList(app, index, pick) { openTaskId = it }
            }
        }
    }
    if (adding) QuickAddSheet(app, index, pick) { adding = false }
}

private fun title(index: TaskIndex?, pick: ListPick): String = when (pick) {
    is ListPick.Smart -> smartTitle(pick.list)
    is ListPick.InProject -> listName(index, pick.id)
    is ListPick.WithTag -> "#" + pick.name
    is ListPick.Custom -> index?.filters?.firstOrNull { it.id == pick.id }?.name ?: "Smart list"
}

@Composable
private fun ListDrawer(app: ZenApp, index: TaskIndex?, current: ListPick, onPick: (ListPick) -> Unit, openSettings: () -> Unit) {
    val counts = remember(index) { index?.let { app.core.sidebarCounts(it) }.orEmpty() }
    fun count(p: ListPick) = counts[app.core.filterKey(p.filter)] ?: 0
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text("ZenTask", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            for (list in listOf(SmartList.TODAY, SmartList.TOMORROW, SmartList.NEXT_7_DAYS, SmartList.INBOX, SmartList.ALL)) {
                val p = ListPick.Smart(list)
                DrawerRow(smartTitle(list), smartIcon(list), null, count(p), current == p) { onPick(p) }
            }
            DrawerHeading("Lists")
            for (project in liveLists(index)) {
                val p = ListPick.InProject(project.id)
                DrawerRow(project.name, Icons.AutoMirrored.Filled.List, ZenColors.hex(project.color), count(p), current == p) { onPick(p) }
            }
            if (!index?.filters.isNullOrEmpty()) {
                DrawerHeading("Smart lists")
                for (f in index!!.filters) {
                    val p = ListPick.Custom(f.id)
                    DrawerRow((f.icon?.let { "$it " } ?: "") + f.name, Icons.Outlined.FilterAlt, null, count(p), current == p) { onPick(p) }
                }
            }
            if (!index?.tags.isNullOrEmpty()) {
                DrawerHeading("Tags")
                for (t in index!!.tags.filter { !it.deleted }) {
                    val p = ListPick.WithTag(t.name)
                    DrawerRow("#" + t.label.ifEmpty { t.name }, Icons.Filled.Tag, ZenColors.hex(t.color), count(p), current == p) { onPick(p) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            for (list in listOf(SmartList.COMPLETED, SmartList.WONT_DO, SmartList.TRASH)) {
                val p = ListPick.Smart(list)
                DrawerRow(smartTitle(list), smartIcon(list), null, 0, current == p) { onPick(p) }
            }
            NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = openSettings, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun DrawerRow(label: String, icon: ImageVector, tint: Color?, count: Int, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant) },
        badge = { if (count > 0) Text("$count") },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun TaskList(app: ZenApp, index: TaskIndex?, pick: ListPick, open: (String) -> Unit) {
    val sections = remember(index, pick) {
        index?.let { app.core.sections(it, pick.filter, app.core.viewOptions(pick.filter)) }.orEmpty()
    }
    val folded = remember(pick) { mutableStateListOf("done") }
    if (index != null && sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text(if (pick == ListPick.Smart(SmartList.TODAY)) "Nothing due today" else "No tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().animateContentSize()) {
        for (section in sections) {
            val isFolded = section.key in folded
            if (section.title.isNotEmpty()) {
                item(key = "h:" + section.key) {
                    SectionHeader(section, isFolded) { if (isFolded) folded.remove(section.key) else folded.add(section.key) }
                }
            }
            if (!isFolded || section.title.isEmpty()) {
                items(section.rows, key = { section.key + ":" + it.task.id }) { row ->
                    TaskRowItem(app, index, row, showList = pick !is ListPick.InProject, barColor = ZenColors.hex(section.color)) { open(row.task.id) }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun SectionHeader(section: TaskSection, folded: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (folded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("${section.rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CheckCircleButton(task: Task, onToggle: () -> Unit) {
    val color = ZenColors.priority(task.priority, MaterialTheme.colorScheme.outline)
    val done = task.isDone
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
            .background(if (done) color.copy(alpha = 0.85f) else Color.Transparent)
            .border(2.dp, color, RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Filled.Check, "Done", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TaskRowItem(app: ZenApp, index: TaskIndex?, row: TaskRow, showList: Boolean, barColor: Color?, open: () -> Unit) {
    val task = row.task
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(start = if (barColor != null) 0.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (barColor != null) {
            Box(Modifier.width(3.dp).height(36.dp).background(barColor))
            Spacer(Modifier.width(13.dp))
        }
        CheckCircleButton(task) { toggleComplete(app, task) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title.ifEmpty { "Untitled" },
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            preview(task)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val day = index?.let { app.core.taskDayMillis(it, task) }
            val meta = buildList {
                if (day != null) add(dateText(day, task))
                if (row.checklistTotal > 0) add("${row.checklistDone}/${row.checklistTotal}")
                task.tags.forEach { add("#$it") }
                if (showList && row.projectName.isNotEmpty()) add(row.projectName)
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.overdue) ZenColors.high else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun toggleComplete(app: ZenApp, task: Task) {
    if (task.isDone || task.status == TaskStatus.WONT_DO) app.core.actions.uncomplete(task.id) else app.core.actions.complete(task.id)
}

/** First non-empty line of the notes, without Markdown marks. */
fun preview(task: Task): String? {
    val text = if (task.kind == Task.KIND_CHECKLIST && task.desc.isNotEmpty()) task.desc else task.content
    return text.lineSequence().map { it.trim().trimStart('#', '>', '*', '-', '•', ' ') }.firstOrNull { it.isNotEmpty() }?.take(200)
}

/** The quick-add bottom sheet: quick-add syntax (dates, #tag, !priority, ~list), chips for what it found. */
@Composable
private fun QuickAddSheet(app: ZenApp, index: TaskIndex?, pick: ListPick, close: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val projects = remember(index) { index?.projects.orEmpty().filter { !it.closed } }
    val parsed = remember(text, projects) { app.core.parseQuickAdd(text, projects, emptySet()) }
    fun add() {
        if (parsed.title.isBlank()) return
        app.core.quickAdd(text, pick.filter, projects, emptySet())
        text = ""
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("What would you like to do?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )
            Text("Try: tomorrow 9am #work !high ~list", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in parsed.tokens) AssistChip(onClick = {}, label = { Text(t.label) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("Close") }
                TextButton(onClick = { add() }, enabled = parsed.title.isNotBlank()) { Text("Add") }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A task's own page: title, notes, date, priority, list, checklist, subtasks; complete or trash. */
@Composable
fun TaskDetailScreen(app: ZenApp, taskId: String, close: () -> Unit) {
    val index by app.index.collectAsState()
    val task = index?.task(taskId)
    androidx.activity.compose.BackHandler(onBack = close)
    if (task == null) {
        LaunchedEffect(index) { if (index != null) close() }
        return
    }
    // Drafts of the two text fields, each with the value it was loaded from. Only a field the user changed
    // is written, so a newer title or note synced from another device is never reverted by an untouched field;
    // an untouched field follows the synced value. Saved while typing (debounced), on stop and on leaving.
    var title by rememberSaveable(taskId) { mutableStateOf(task.title) }
    var titleBase by rememberSaveable(taskId) { mutableStateOf(task.title) }
    var notes by rememberSaveable(taskId) { mutableStateOf(task.content) }
    var notesBase by rememberSaveable(taskId) { mutableStateOf(task.content) }
    var picking by remember { mutableStateOf<String?>(null) }
    var newItem by remember { mutableStateOf("") }
    var newSub by remember { mutableStateOf("") }
    if (title == titleBase && task.title != titleBase) { title = task.title; titleBase = task.title }
    if (notes == notesBase && task.content != notesBase) { notes = task.content; notesBase = task.content }
    fun commit() {
        val t = title.trim()
        if (title != titleBase && t.isNotEmpty()) { app.core.actions.rename(taskId, t); titleBase = title }
        if (notes != notesBase) { app.core.actions.setContent(taskId, notes); notesBase = notes }
    }
    LaunchedEffect(title, notes) {
        kotlinx.coroutines.delay(800)
        commit()
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(taskId, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) commit() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); commit() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listName(index, task.projectId)) },
                navigationIcon = { IconButton(onClick = { commit(); close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { app.core.actions.trash(task.id); close() }) { Icon(Icons.Filled.Delete, "Move to Trash") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckCircleButton(task) { toggleComplete(app, task) }
                Spacer(Modifier.width(12.dp))
                TextField(
                    value = title, onValueChange = { title = it },
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                )
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                val day = index?.let { app.core.taskDayMillis(it, task) }
                AssistChip(onClick = { picking = "date" }, label = { Text(day?.let { dateText(it, task) } ?: "Add date") }, leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) })
                Box {
                    AssistChip(onClick = { picking = "priority" }, label = { Text(priorityTitle(task.priority)) },
                        leadingIcon = { Icon(Icons.Filled.Flag, null, tint = ZenColors.priority(task.priority, MaterialTheme.colorScheme.outline)) })
                    DropdownMenu(expanded = picking == "priority", onDismissRequest = { picking = null }) {
                        for ((p, name) in listOf(5 to "High", 3 to "Medium", 1 to "Low", 0 to "None")) {
                            DropdownMenuItem(text = { Text(name) }, leadingIcon = { Icon(Icons.Filled.Flag, null, tint = ZenColors.priority(p, MaterialTheme.colorScheme.outline)) },
                                onClick = { app.core.actions.setPriority(task.id, p); picking = null })
                        }
                    }
                }
                Box {
                    AssistChip(onClick = { picking = "list" }, label = { Text(listName(index, task.projectId), maxLines = 1) })
                    DropdownMenu(expanded = picking == "list", onDismissRequest = { picking = null }) {
                        DropdownMenuItem(text = { Text("Inbox") }, onClick = { app.core.actions.move(task.id, Project.INBOX_ID); picking = null })
                        for (p in liveLists(index)) DropdownMenuItem(text = { Text(p.name) }, onClick = { app.core.actions.move(task.id, p.id); picking = null })
                    }
                }
                if (day != null) ReminderChip(app, task, expanded = picking == "reminder", open = { picking = "reminder" }, close = { picking = null })
                if (day != null) RepeatChip(app, task, day, expanded = picking == "repeat", open = { picking = "repeat" }, close = { picking = null })
            }
            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), minLines = 3,
            )
            val items = index?.items(task.id).orEmpty()
            if (items.isNotEmpty() || task.kind == Task.KIND_CHECKLIST) {
                Text("Checklist", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                for (item in items) {
                    Row(Modifier.fillMaxWidth().clickable { app.core.actions.toggleItem(item.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.status != 0) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(item.title, textDecoration = if (item.status != 0) TextDecoration.LineThrough else null)
                    }
                }
                AddLine("Add item", newItem, { newItem = it }) { app.core.actions.addItem(task.id, newItem.trim()); newItem = "" }
            }
            if (task.parentId == null) {
                Text("Subtasks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                for (sub in index?.subtasks(task.id).orEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckCircleButton(sub) { toggleComplete(app, sub) }
                        Spacer(Modifier.width(10.dp))
                        Text(sub.title, textDecoration = if (sub.isDone) TextDecoration.LineThrough else null)
                    }
                }
                AddLine("Add subtask", newSub, { newSub = it }) { runCatching { app.core.actions.addSubtask(task.id, newSub.trim()) }; newSub = "" }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
    if (picking == "date") DueDialog(app, task) { picking = null }
}

/** Repeat for a dated task: the same presets as the Mac app (based on the task's day), or none. */
@Composable
private fun RepeatChip(app: ZenApp, task: Task, dayMillis: Long, expanded: Boolean, open: () -> Unit, close: () -> Unit) {
    val date = Instant.ofEpochMilli(dayMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val code = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[date.dayOfWeek.value - 1]
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    val presets = listOf(
        "RRULE:FREQ=DAILY;INTERVAL=1" to "Daily",
        "RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,TU,WE,TH,FR" to "Every weekday (Mon–Fri)",
        "RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=$code" to "Weekly on $weekday",
        "RRULE:FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=${date.dayOfMonth}" to "Monthly on day ${date.dayOfMonth}",
        "RRULE:FREQ=YEARLY;INTERVAL=1" to "Yearly on ${date.format(DateTimeFormatter.ofPattern("d MMMM"))}",
    )
    val rule = task.repeatRule
    Box {
        AssistChip(onClick = open, label = { Text(rule?.let { app.core.describeRepeat(it) } ?: "Repeat", maxLines = 1) }, leadingIcon = {
            Icon(Icons.Filled.Repeat, null, tint = if (rule != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        })
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            for ((r, title) in presets) {
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Icon(if (r == rule) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { app.core.actions.setRepeat(task.id, r, task.repeatFrom); close() },
                )
            }
            if (rule != null) {
                DropdownMenuItem(text = { Text("Don't repeat") }, onClick = { app.core.actions.setRepeat(task.id, null, null); close() })
            }
        }
    }
}

/** Reminders for a dated task: the shared presets for timed or all-day tasks, toggled on and off. */
@Composable
private fun ReminderChip(app: ZenApp, task: Task, expanded: Boolean, open: () -> Unit, close: () -> Unit) {
    val presets = if (task.isAllDay) com.vuhpquang.zentask.tasks.ReminderPresets.allDay else com.vuhpquang.zentask.tasks.ReminderPresets.timed
    val label = when (task.reminders.size) {
        0 -> "Reminder"
        1 -> com.vuhpquang.zentask.tasks.ReminderPresets.title(task.reminders[0]) ?: "1 reminder"
        else -> "${task.reminders.size} reminders"
    }
    Box {
        AssistChip(onClick = open, label = { Text(label, maxLines = 1) }, leadingIcon = {
            Icon(if (task.reminders.isEmpty()) Icons.Outlined.NotificationsNone else Icons.Filled.Notifications, null)
        })
        DropdownMenu(expanded = expanded, onDismissRequest = close) {
            for ((trigger, title) in presets) {
                val on = trigger in task.reminders
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { Icon(if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { app.core.actions.setReminders(task.id, if (on) task.reminders - trigger else task.reminders + trigger) },
                )
            }
            if (task.reminders.isNotEmpty()) {
                DropdownMenuItem(text = { Text("No reminder") }, onClick = { app.core.actions.setReminders(task.id, emptyList()); close() })
            }
        }
    }
}

fun priorityTitle(p: Int) = when (p) { 5 -> "High"; 3 -> "Medium"; 1 -> "Low"; else -> "Priority" }

@Composable
private fun AddLine(hint: String, value: String, onChange: (String) -> Unit, submit: () -> Unit) {
    TextField(
        value = value, onValueChange = onChange, placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Filled.Add, null) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) submit() }),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
    )
}

/** Date (and optional time) for a task; Clear removes it. */
@Composable
private fun DueDialog(app: ZenApp, task: Task, close: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val current = (task.startDate ?: task.dueDate)?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = (current?.toLocalDate() ?: LocalDate.now(zone)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    var withTime by remember { mutableStateOf(!task.isAllDay && current != null) }
    val timeState = rememberTimePickerState(initialHour = current?.hour ?: 9, initialMinute = current?.minute ?: 0, is24Hour = true)
    DatePickerDialog(
        onDismissRequest = close,
        confirmButton = {
            TextButton(onClick = {
                val picked = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() } ?: LocalDate.now(zone)
                val at = if (withTime) picked.atTime(LocalTime.of(timeState.hour, timeState.minute)).atZone(zone) else picked.atStartOfDay(zone)
                app.core.actions.setDue(task.id, at.toInstant().toEpochMilli(), !withTime)
                close()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = { app.core.actions.setDue(task.id, null, false); close() }) { Text("Clear") }
        },
    ) {
        Column {
            DatePicker(state = dateState, showModeToggle = false)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Time", Modifier.weight(1f))
                Switch(checked = withTime, onCheckedChange = { withTime = it })
            }
            AnimatedVisibility(withTime) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = timeState) }
            }
        }
    }
}

/** A simple confirm dialog. */
@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private val circle = CircleShape
