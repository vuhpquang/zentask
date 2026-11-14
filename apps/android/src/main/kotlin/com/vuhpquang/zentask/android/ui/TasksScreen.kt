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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.focus.onFocusChanged
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
    /** The Eisenhower Matrix (open tasks by priority). */
    data object Matrix : ListPick

    val filter: TaskFilter
        get() = when (this) {
            is Smart -> TaskFilter.Smart(list)
            is InProject -> TaskFilter.InProject(id)
            is WithTag -> TaskFilter.WithTag(name)
            is Custom -> TaskFilter.Custom(id)
            Matrix -> TaskFilter.Smart(SmartList.ALL)
        }

    val key: String
        get() = when (this) {
            is Smart -> "smart:${list.name}"
            is InProject -> "project:$id"
            is WithTag -> "tag:$name"
            is Custom -> "custom:$id"
            Matrix -> "matrix:"
        }

    companion object {
        fun parse(key: String): ListPick {
            val (kind, value) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return when (kind) {
                "project" -> InProject(value)
                "tag" -> WithTag(value)
                "custom" -> Custom(value)
                "matrix" -> Matrix
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
fun TasksScreen(app: ZenApp, openSettings: () -> Unit, openSearch: () -> Unit, openStats: () -> Unit = {}) {
    val index by app.index.collectAsState()
    var pickKey by rememberSaveable { mutableStateOf("smart:TODAY") }
    val pick = ListPick.parse(pickKey)
    var openTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val quickAddAsked by app.quickAdd.collectAsState()
    androidx.compose.runtime.LaunchedEffect(quickAddAsked) { if (quickAddAsked) { adding = true; app.quickAdd.value = false } }
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var sorting by remember { mutableStateOf(false) }
    var planning by remember { mutableStateOf(false) }

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
            }, openStats = {
                scope.launch { drawer.close() }
                openStats()
            })
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title(index, pick), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Lists") } },
                    actions = {
                        IconButton(onClick = openSearch) { Icon(Icons.Filled.Search, "Search") }
                        if (pick == ListPick.Smart(SmartList.TODAY)) IconButton(onClick = { planning = true }) { Icon(Icons.Filled.WbTwilight, "Plan my day") }
                        if (pick != ListPick.Matrix) IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Group and sort") }
                        val project = (pick as? ListPick.InProject)?.let { p -> index?.projects?.firstOrNull { it.id == p.id } }
                        if (project != null && project.id != Project.INBOX_ID) ListMenu(app, project) { pickKey = ListPick.Smart(SmartList.INBOX).key }
                        val filter = (pick as? ListPick.Custom)?.let { c -> index?.filters?.firstOrNull { it.id == c.id } }
                        if (filter != null) FilterMenu(app, index, filter) { pickKey = ListPick.Smart(SmartList.TODAY).key }
                    },
                )
            },
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
            floatingActionButton = {
                val kanban = (pick as? ListPick.InProject)?.let { p -> index?.projects?.firstOrNull { it.id == p.id }?.viewMode == "kanban" } == true
                if (pick != ListPick.Matrix && !kanban && (pick !is ListPick.Smart || pick.list !in setOf(SmartList.COMPLETED, SmartList.WONT_DO, SmartList.TRASH))) {
                    FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add task") }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val project = (pick as? ListPick.InProject)?.let { p -> index?.projects?.firstOrNull { it.id == p.id } }
                when {
                    pick == ListPick.Matrix -> MatrixBoard(app, index) { openTaskId = it }
                    project != null && project.viewMode == "kanban" -> KanbanBoard(app, index, project) { openTaskId = it }
                    else -> TaskList(app, index, pick, snackbar) { openTaskId = it }
                }
            }
        }
    }
    if (adding) QuickAddSheet(app, index, pick) { adding = false }
    if (sorting) SortSheet(app, pick) { sorting = false }
    index?.let { idx -> if (planning) PlanDayDialog(app, idx) { planning = false } }
}

private val LIST_COLORS = listOf("#5B5FE9", "#8B5CF6", "#EC4899", "#EF4444", "#F59E0B", "#10B981", "#06B6D4", "#64748B")

/** "…" for a list: rename and recolour, or move it to Trash (its tasks go with it and come back with it). */
@Composable
private fun ListMenu(app: ZenApp, project: Project, left: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var trashing by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "List options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit list") }, onClick = { open = false; editing = true })
            val kanban = project.viewMode == "kanban"
            DropdownMenuItem(text = { Text(if (kanban) "View as list" else "View as kanban") }, onClick = {
                open = false
                app.core.listActions.setViewMode(project.id, if (kanban) "list" else "kanban")
            })
            DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { open = false; trashing = true })
        }
    }
    if (editing) {
        ListDialog("Edit list", project.name, project.color ?: LIST_COLORS.first(), confirm = "Save", close = { editing = false }) { name, color ->
            if (name != project.name) app.core.listActions.rename(project.id, name)
            if (color != project.color) app.core.listActions.setColor(project.id, color)
        }
    }
    if (trashing) {
        AlertDialog(
            onDismissRequest = { trashing = false },
            title = { Text("Move \"${project.name}\" to Trash?") },
            text = { Text("Its tasks move to Trash with it. Restore the list from Trash on the Mac to bring them back.") },
            confirmButton = { TextButton(onClick = { trashing = false; app.core.listActions.trash(project.id); left() }) { Text("Move to Trash") } },
            dismissButton = { TextButton(onClick = { trashing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListDialog(heading: String, initialName: String, initialColor: String, confirm: String, close: () -> Unit, save: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(heading) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in LIST_COLORS) {
                        val selected = c.equals(color, ignoreCase = true)
                        Box(
                            Modifier.size(26.dp).background(ZenColors.hex(c) ?: Color.Gray, androidx.compose.foundation.shape.CircleShape)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Filled.Check, "Selected", tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(name.trim(), color); close() }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

private fun title(index: TaskIndex?, pick: ListPick): String = when (pick) {
    is ListPick.Smart -> smartTitle(pick.list)
    is ListPick.InProject -> listName(index, pick.id)
    is ListPick.WithTag -> "#" + pick.name
    is ListPick.Custom -> index?.filters?.firstOrNull { it.id == pick.id }?.name ?: "Smart list"
    ListPick.Matrix -> "Eisenhower Matrix"
}

@Composable
private fun ListDrawer(app: ZenApp, index: TaskIndex?, current: ListPick, onPick: (ListPick) -> Unit, openSettings: () -> Unit, openStats: () -> Unit) {
    val counts = remember(index) { index?.let { app.core.sidebarCounts(it) }.orEmpty() }
    fun count(p: ListPick) = counts[app.core.filterKey(p.filter)] ?: 0
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text("ZenTask", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            for (list in listOf(SmartList.TODAY, SmartList.TOMORROW, SmartList.NEXT_7_DAYS, SmartList.INBOX, SmartList.ALL)) {
                val p = ListPick.Smart(list)
                DrawerRow(smartTitle(list), smartIcon(list), null, count(p), current == p) { onPick(p) }
            }
            DrawerRow("Eisenhower Matrix", Icons.Filled.GridView, null, 0, current == ListPick.Matrix) { onPick(ListPick.Matrix) }
            var creating by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DrawerHeading("Lists") }
                IconButton(onClick = { creating = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(Icons.Filled.Add, "New list") }
            }
            if (creating) {
                ListDialog("New list", initialName = "", initialColor = LIST_COLORS.first(), confirm = "Create", close = { creating = false }) { name, color ->
                    val created = app.core.listActions.createList(name, color)
                    onPick(ListPick.InProject(created.id))
                }
            }
            for (project in liveLists(index)) {
                val p = ListPick.InProject(project.id)
                DrawerRow(project.name, Icons.AutoMirrored.Filled.List, ZenColors.hex(project.color), count(p), current == p) { onPick(p) }
            }
            var newFilter by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { DrawerHeading("Smart lists") }
                IconButton(onClick = { newFilter = true }, modifier = Modifier.padding(end = 12.dp)) { Icon(Icons.Filled.Add, "New smart list") }
            }
            if (newFilter) FilterEditorDialog(app, index, null, close = { newFilter = false }) { onPick(ListPick.Custom(it.id)) }
            if (!index?.filters.isNullOrEmpty()) {
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
            val update by app.updater.state.collectAsState()
            (update as? com.vuhpquang.zentask.android.AppUpdater.State.Available)?.let { u ->
                NavigationDrawerItem(
                    label = { Text("Update to ${u.update.version}", color = MaterialTheme.colorScheme.primary) },
                    selected = false, onClick = openSettings, modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            NavigationDrawerItem(label = { Text("Statistics") }, selected = false, onClick = openStats, modifier = Modifier.padding(horizontal = 12.dp))
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
private fun TaskList(app: ZenApp, index: TaskIndex?, pick: ListPick, snackbar: androidx.compose.material3.SnackbarHostState, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val viewOptions = remember(index, pick) { app.core.viewOptions(pick.filter) }
    val sections = remember(index, pick) {
        index?.let { app.core.sections(it, pick.filter, viewOptions) }.orEmpty()
    }
    val folded = remember(pick) { mutableStateListOf("done") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("zentask.plan", android.content.Context.MODE_PRIVATE) }
    var planDismissed by remember { mutableStateOf(prefs.getInt("lastShown", 0) == app.core.todayStamp()) }
    var planning by remember { mutableStateOf(false) }
    val planCount = remember(index) { index?.let { app.core.dayPlan(it).sumOf { s -> s.rows.size } } ?: 0 }
    val showPlanCard = pick == ListPick.Smart(SmartList.TODAY) && !planDismissed && planCount > 0
    fun dismissPlan() { planDismissed = true; prefs.edit().putInt("lastShown", app.core.todayStamp()).apply() }
    if (planning && index != null) PlanDayDialog(app, index) { planning = false; dismissPlan() }
    if (index != null && sections.isEmpty() && !showPlanCard) {
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
        if (showPlanCard) item(key = "plan") {
            androidx.compose.material3.ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).animateItem()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WbTwilight, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Plan your day", fontWeight = FontWeight.SemiBold)
                        Text("$planCount overdue or upcoming tasks to pick from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { planning = true }) { Text("Plan") }
                    IconButton(onClick = { dismissPlan() }) { Icon(Icons.Filled.Close, "Not today") }
                }
            }
        }
        for (section in sections) {
            val isFolded = section.key in folded
            if (section.title.isNotEmpty()) {
                item(key = "h:" + section.key) {
                    Box(Modifier.animateItem()) {
                        SectionHeader(section, isFolded, trailing = if (section.key == "date:Overdue") ("Move to today" to { index?.let { app.core.moveOverdueToToday(it) }; Unit }) else null) {
                            if (isFolded) folded.remove(section.key) else folded.add(section.key)
                        }
                    }
                }
            }
            if (!isFolded || section.title.isEmpty()) {
                items(section.rows, key = { section.key + ":" + it.task.id }) { row ->
                    // Trash has its own Restore / Delete forever in the task's menu.
                    Column(Modifier.animateItem()) {
                        SwipeTaskRow(app, row.task, enabled = row.task.trashedAt == null, trashed = { offerUndoTrash(app, it, snackbar, scope) }) {
                            TaskRowItem(app, index, row, showList = pick !is ListPick.InProject, barColor = ZenColors.hex(section.color), showDetails = !viewOptions.hideDetails) { open(row.task.id) }
                        }
                        // Subtasks under their parent, indented like on the Mac; each has its own check and swipe.
                        for (sub in row.subtasks) {
                            SwipeTaskRow(app, sub, enabled = sub.trashedAt == null, trashed = { offerUndoTrash(app, it, snackbar, scope) }) {
                                SubtaskRow(app, sub) { open(sub.id) }
                            }
                        }
                    }
                }

            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun SectionHeader(section: TaskSection, folded: Boolean, trailing: Pair<String, () -> Unit>? = null, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (folded) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("${section.rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = trailing.second) { Text(trailing.first) }
        }
    }
}

/** "Plan my day": tick overdue / upcoming / important undated tasks to put on today (times kept). */
@Composable
fun PlanDayDialog(app: ZenApp, index: TaskIndex, close: () -> Unit) {
    val sections = remember(index) { app.core.dayPlan(index) }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Plan my day") },
        text = {
            if (sections.isEmpty()) Text("Nothing to plan: no overdue, upcoming or important tasks.")
            else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                for (s in sections) {
                    item(key = s.key) { Text(s.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                    items(s.rows, key = { s.key + it.task.id }) { row ->
                        val id = row.task.id
                        Row(Modifier.fillMaxWidth().clickable { if (id in picked) picked.remove(id) else picked.add(id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = id in picked, onCheckedChange = { if (it) picked.add(id) else picked.remove(id) })
                            Column(Modifier.weight(1f)) {
                                Text(row.task.title.ifEmpty { "Untitled" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val day = app.core.taskDayMillis(index, row.task)
                                if (day != null) Text(dateText(day, row.task), style = MaterialTheme.typography.labelSmall,
                                    color = if (row.overdue) ZenColors.high else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { app.core.planToday(picked.toList()); close() }) {
                Text(if (picked.isEmpty()) "Add to today" else "Add ${picked.size} to today")
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
fun CheckCircleButton(task: Task, onToggle: () -> Unit) {
    val wontDo = task.status == TaskStatus.WONT_DO
    val color = if (wontDo) MaterialTheme.colorScheme.outline else ZenColors.priority(task.priority, MaterialTheme.colorScheme.outline)
    // Ticking shows the check first, then completes (the row leaves the list a moment later).
    var ticked by remember(task.id, task.isDone) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val done = task.isDone || wontDo || ticked
    val fill by androidx.compose.animation.animateColorAsState(if (done) color.copy(alpha = 0.85f) else Color.Transparent, label = "check fill")
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
            .background(fill)
            .border(2.dp, color, RoundedCornerShape(6.dp))
            .clickable {
                if (task.isDone || wontDo) onToggle()
                else if (!ticked) {
                    ticked = true
                    scope.launch { kotlinx.coroutines.delay(350); onToggle() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(done, enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            if (wontDo) Icon(Icons.Filled.Close, "Won't do", tint = Color.White, modifier = Modifier.size(16.dp))
            else Icon(Icons.Filled.Check, "Done", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TaskRowItem(app: ZenApp, index: TaskIndex?, row: TaskRow, showList: Boolean, barColor: Color?, showDetails: Boolean = true, open: () -> Unit) {
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
            if (showDetails) preview(task)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    return text.lineSequence().map { com.vuhpquang.zentask.tasks.MarkdownLite.plain(it.trim()).trimStart('#', '>', '*', '-', '•', ' ') }.firstOrNull { it.isNotEmpty() }?.take(200)
}

/** The quick-add bottom sheet: quick-add syntax (dates, #tag, !priority, ~list), chips for what it found. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            val templates = remember(index) { app.core.templateActions.list() }
            if (templates.isNotEmpty()) {
                Text("From a template (long-press to delete)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                var deleting by remember { mutableStateOf<com.vuhpquang.zentask.model.TaskTemplate?>(null) }
                Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tpl in templates) {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .combinedClickable(onClick = {
                                    app.core.templateActions.use(tpl.id, pick.filter, (pick as? ListPick.InProject)?.id)
                                    close()
                                }, onLongClick = { deleting = tpl })
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.NoteAdd, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(tpl.name.ifBlank { tpl.title }, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                deleting?.let { tpl ->
                    Confirm("Delete template \"${tpl.name}\"?", "Tasks already made from it stay.", "Delete",
                        onConfirm = { app.core.templateActions.delete(tpl.id); deleting = null; close() }, onDismiss = { deleting = null })
                }
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
                actions = { TaskMenu(app, task) { close() } },
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
                TagChips(app, index, task)
            }
            var editingNotes by remember(taskId) { mutableStateOf(false) }
            if (!editingNotes && com.vuhpquang.zentask.tasks.MarkdownLite.hasMarkdown(notes)) {
                // Markdown shown formatted; tap to edit, tap a to-do box to tick it.
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .clickable { editingNotes = true }.padding(12.dp),
                ) {
                    MarkdownNotes(notes) { i -> notes = com.vuhpquang.zentask.tasks.MarkdownLite.toggleTodo(notes, i); commit() }
                }
            } else {
                val focusNotes = remember { FocusRequester() }
                var notesHadFocus by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes (Markdown)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focusNotes)
                        .onFocusChanged { f ->
                            // Edit mode from the first focus until focus really leaves (the first callback is "unfocused"),
                            // so typing Markdown never swaps the field out.
                            if (f.isFocused) { editingNotes = true; notesHadFocus = true } else if (notesHadFocus) { editingNotes = false; notesHadFocus = false }
                        },
                    minLines = 3,
                )
                LaunchedEffect(editingNotes) { if (editingNotes) runCatching { focusNotes.requestFocus() } }
            }
            val rev by app.revision.collectAsState()
            AttachmentsSection(app, task.id, rev)
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Quick picks, like the Mac's date popover.
            val today = LocalDate.now(zone)
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, d) in listOf("Today" to today, "Tomorrow" to today.plusDays(1), "Next week" to today.plusDays((8 - today.dayOfWeek.value).toLong()))) {
                    AssistChip(onClick = { dateState.selectedDateMillis = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }, label = { Text(label) })
                }
            }
            // No title/headline: the quick picks say it, and the time row needs the room.
            DatePicker(state = dateState, showModeToggle = false, title = null, headline = null)
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

@Composable
private fun SubtaskRow(app: ZenApp, task: Task, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(start = 48.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CheckCircleButton(task) { toggleComplete(app, task) }
        Spacer(Modifier.width(12.dp))
        Text(
            task.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
