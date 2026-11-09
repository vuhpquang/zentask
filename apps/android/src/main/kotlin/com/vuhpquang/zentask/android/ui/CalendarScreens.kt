package com.vuhpquang.zentask.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.calendar.CalendarDay
import com.vuhpquang.zentask.calendar.CalendarEntry
import com.vuhpquang.zentask.calendar.CalendarViewOptions
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun midnight(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

private val HOUR_HEIGHT = 56.dp
private val GUTTER = 44.dp
private const val SWIPE_DISTANCE = 120f

/** Day / 3 days / Week / Month / List, as on the Mac. */
private enum class CalMode(val title: String, val days: Int) { DAY("Day", 1), THREE("3 days", 3), WEEK("Week", 7), MONTH("Month", 0), LIST("List", 0) }

/** Working hours the grid opens on (Settings → Calendar), shared with nothing: per device. */
private fun workStart(context: Context): Int = context.getSharedPreferences("zentask.calendar", Context.MODE_PRIVATE).getInt("workStart", 8)

/** Colour of an entry: tasks by priority (or their list, per the view options), the rest by their own colour. */
@Composable
fun entryColor(e: CalendarEntry): Color = when (e.kind) {
    "task", "repeat", "checklist" -> ZenColors.hex(e.color) ?: ZenColors.priority(e.priority, MaterialTheme.colorScheme.primary)
    "focus" -> ZenColors.violet
    else -> ZenColors.hex(e.color) ?: MaterialTheme.colorScheme.primary
}

/** What a tap on an entry does: open its task, toggle a habit; events show their details. */
private fun openEntry(app: ZenApp, e: CalendarEntry, showEvent: (CalendarEntry) -> Unit) {
    when (e.kind) {
        "task" -> app.openTask.value = e.id
        "repeat", "checklist" -> e.taskId?.let { app.openTask.value = it }
        "habit" -> app.core.toggleHabitEntry(e.id)
        else -> showEvent(e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(app: ZenApp) {
    val revision by app.revision.collectAsState()
    var modeName by rememberSaveable { mutableStateOf(CalMode.WEEK.name) }
    val mode = CalMode.valueOf(modeName)
    var anchor by rememberSaveable { mutableLongStateOf(LocalDate.now(zone).toEpochDay()) }
    val selected = LocalDate.ofEpochDay(anchor)
    var options by remember(revision) { mutableStateOf(app.core.calendarView()) }
    var showOptions by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var event by remember { mutableStateOf<CalendarEntry?>(null) }
    var newAt by remember { mutableStateOf<Long?>(null) }

    val first = when (mode) {
        CalMode.WEEK -> selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        CalMode.MONTH -> selected.withDayOfMonth(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        CalMode.LIST -> selected
        else -> selected
    }
    val count = when (mode) { CalMode.MONTH -> 42; CalMode.LIST -> 14; else -> mode.days }
    val days = loaded(revision, first, count) { app.core.calendarDays(midnight(first), count) } ?: emptyList()
    fun shift(dir: Int) {
        anchor = when (mode) {
            CalMode.MONTH -> selected.plusMonths(dir.toLong()).toEpochDay()
            CalMode.LIST -> selected.plusDays(14L * dir).toEpochDay()
            else -> selected.plusDays(mode.days.toLong() * dir).toEpochDay()
        }
    }
    val title = when (mode) {
        CalMode.DAY -> selected.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
        CalMode.MONTH -> selected.format(DateTimeFormatter.ofPattern("MMM yyyy"))
        else -> {
            val last = first.plusDays((count - 1).toLong())
            if (first.month == last.month) "${first.dayOfMonth}–${last.dayOfMonth} ${last.format(DateTimeFormatter.ofPattern("MMM"))}"
            else "${first.format(DateTimeFormatter.ofPattern("d MMM"))} – ${last.format(DateTimeFormatter.ofPattern("d MMM"))}"
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                TextButton(onClick = { anchor = LocalDate.now(zone).toEpochDay() }) { Text("Today") }
                IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
                Box {
                    TextButton(onClick = { modeMenu = true }) { Text(mode.title) }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        for (m in CalMode.entries) DropdownMenuItem(text = { Text(m.title) }, onClick = { modeName = m.name; modeMenu = false })
                    }
                }
                IconButton(onClick = { showOptions = true }) { Icon(Icons.Filled.Tune, "View options") }
            },
        )
    }) { padding ->
        var drag by remember { mutableStateOf(0f) }
        Box(
            Modifier.padding(padding).fillMaxSize().pointerInput(mode, anchor) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (abs(drag) > SWIPE_DISTANCE) shift(if (drag < 0) 1 else -1); drag = 0f },
                    onHorizontalDrag = { _, d -> drag += d },
                )
            },
        ) {
            when (mode) {
                CalMode.MONTH -> MonthView(app, days, selected, options) { anchor = it.toEpochDay(); modeName = CalMode.DAY.name }
                CalMode.LIST -> ListView(app, days) { event = it }
                else -> TimeGrid(app, days, options, onEvent = { event = it }, onEmpty = { newAt = it })
            }
        }
    }
    if (showOptions) ViewOptionsSheet(options, close = { showOptions = false }) { options = it; app.core.setCalendarView(it) }
    event?.let { e -> EventDialog(e) { event = null } }
    newAt?.let { at -> NewTaskAtDialog(app, at) { newAt = null } }
}

// MARK: Time grid

@Composable
private fun TimeGrid(app: ZenApp, days: List<CalendarDay>, options: CalendarViewOptions, onEvent: (CalendarEntry) -> Unit, onEmpty: (Long) -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val today = LocalDate.now(zone)
    // Open on the working hours, with room for the first hour's label.
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (HOUR_HEIGHT * workStart(context) - 12.dp).roundToPx() }) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize()) {
        // Day headers and the all-day row.
        Row(Modifier.fillMaxWidth().padding(start = GUTTER)) {
            for (d in days) {
                val date = Instant.ofEpochMilli(d.dayStart).atZone(zone).toLocalDate()
                val isToday = date == today
                Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${date.dayOfMonth}", fontWeight = FontWeight.SemiBold,
                            color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                    if (options.showLunar && days.size <= 3) {
                        Text(app.core.lunarLabel(d.dayStart), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (days.any { it.allDay.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
                Text("All day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(GUTTER).padding(top = 4.dp, start = 4.dp))
                for (d in days) {
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (e in d.allDay) EntryChip(e, compact = days.size > 3) { openEntry(app, e, onEvent) }
                    }
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24)) {
                Column(Modifier.width(GUTTER)) {
                    for (h in 0 until 24) {
                        Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                            if (h > 0) Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(y = (-7).dp).padding(start = 4.dp))
                        }
                    }
                }
                for (d in days) {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxHeight()
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            .pointerInput(d.dayStart) {
                                detectTapGestures { pos ->
                                    // Snap to the half hour that was tapped.
                                    val minutes = (pos.y / HOUR_HEIGHT.toPx() * 60).toInt() / 30 * 30
                                    onEmpty(d.dayStart + minutes * 60_000L)
                                }
                            },
                    ) {
                        val colWidth = maxWidth
                        for (h in 1 until 24) {
                            HorizontalDivider(Modifier.offset(y = HOUR_HEIGHT * h), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        for (p in d.timed) {
                            val top = HOUR_HEIGHT * (p.startMinute / 60f)
                            val height = (HOUR_HEIGHT * ((p.endMinute - p.startMinute).coerceAtLeast(20) / 60f)) - 2.dp
                            val w = colWidth / p.columns
                            Box(Modifier.offset(x = w * p.column, y = top).width(w - 2.dp).height(height)) {
                                Block(p.entry, height, options) { openEntry(app, p.entry, onEvent) }
                            }
                        }
                        if (Instant.ofEpochMilli(d.dayStart).atZone(zone).toLocalDate() == today) {
                            val minutes = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }.toFloat()
                            Box(Modifier.offset(y = HOUR_HEIGHT * (minutes / 60f)).fillMaxWidth().height(2.dp).background(ZenColors.high))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(e: CalendarEntry, height: Dp, options: CalendarViewOptions, onClick: () -> Unit) {
    val color = entryColor(e)
    val modern = options.itemStyle == "modern"
    val fill = if (modern) color.copy(alpha = 0.2f) else color.copy(alpha = if (e.done) 0.45f else 0.9f)
    val text = if (modern) MaterialTheme.colorScheme.onSurface else Color.White
    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(fill).clickable(onClick = onClick)) {
        if (modern) Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                e.title.ifEmpty { "Untitled" }, color = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 13.sp,
                maxLines = if (height < 40.dp) 1 else 3, overflow = TextOverflow.Ellipsis,
                textDecoration = if (e.done) TextDecoration.LineThrough else null,
            )
            if (height >= 40.dp) Text(hm(e.start), color = text.copy(alpha = 0.85f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun EntryChip(e: CalendarEntry, compact: Boolean, onClick: () -> Unit) {
    val color = entryColor(e)
    Text(
        e.title.ifEmpty { "Untitled" }, color = Color.White, fontSize = if (compact) 10.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textDecoration = if (e.done) TextDecoration.LineThrough else null,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = if (e.done) 0.45f else 0.9f))
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

// MARK: Month

@Composable
private fun MonthView(app: ZenApp, days: List<CalendarDay>, selected: LocalDate, options: CalendarViewOptions, openDay: (LocalDate) -> Unit) {
    val today = LocalDate.now(zone)
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(java.time.DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (week in 0 until 6) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 7) {
                    val d = days.getOrNull(week * 7 + col) ?: continue
                    val date = Instant.ofEpochMilli(d.dayStart).atZone(zone).toLocalDate()
                    val inMonth = date.month == selected.month
                    val entries = d.allDay + d.timed.map { it.entry }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            .clickable { openDay(date) }.padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(if (date == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${date.dayOfMonth}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = when {
                                    date == today -> MaterialTheme.colorScheme.onPrimary
                                    inMonth -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.outline
                                })
                            }
                            if (options.showLunar) {
                                Text(app.core.lunarLabel(d.dayStart), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
                            }
                        }
                        // Three fit; with more, two and a "+N" line.
                        val shown = if (entries.size <= 3) entries else entries.take(2)
                        for (e in shown) {
                            val c = entryColor(e)
                            Text(e.title, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, color = Color.White,
                                textDecoration = if (e.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = if (e.done) 0.45f else 0.85f)).padding(horizontal = 2.dp))
                        }
                        if (entries.size > shown.size) Text("+${entries.size - shown.size}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// MARK: List

@Composable
private fun ListView(app: ZenApp, days: List<CalendarDay>, onEvent: (CalendarEntry) -> Unit) {
    val today = LocalDate.now(zone)
    val withItems = days.filter { it.allDay.isNotEmpty() || it.timed.isNotEmpty() }
    if (withItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing planned in these two weeks", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        for (d in withItems) {
            val date = Instant.ofEpochMilli(d.dayStart).atZone(zone).toLocalDate()
            item(key = "h${d.dayStart}") {
                Text(
                    when (date) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMM")) },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            items(d.allDay + d.timed.map { it.entry }, key = { "${d.dayStart}:${it.id}" }) { e -> ListRow(e) { openEntry(app, e, onEvent) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ListRow(e: CalendarEntry, onClick: () -> Unit) {
    val color = entryColor(e)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (e.allDay) "All day" else hm(e.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).background(color))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (!e.allDay && e.kind == "event") Text("${hm(e.start)} – ${hm(e.end)}", style = MaterialTheme.typography.labelSmall, color = color)
                Text((if (e.done) "✓ " else "") + e.title.ifEmpty { "Untitled" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (e.done) TextDecoration.LineThrough else null)
            }
        }
    }
}

// MARK: Dialogs

@Composable
private fun EventDialog(e: CalendarEntry, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(e.title.ifEmpty { "Untitled" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val date = Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"))
                Text(if (e.allDay) "$date · All day" else "$date · ${hm(e.start)} – ${hm(e.end)}")
                if (e.location.isNotBlank()) Text(e.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.kind == "event") Text("From your calendar (read-only)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** Tap on an empty slot: a task at that time (30 minutes). */
@Composable
private fun NewTaskAtDialog(app: ZenApp, at: Long, close: () -> Unit) {
    var title by remember { mutableStateOf("") }
    val label = Instant.ofEpochMilli(at).atZone(zone).let { it.toLocalDate().format(DateTimeFormatter.ofPattern("EEE, d MMM")) + " · " + it.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val t = app.core.actions.create(title, filter = null, due = at, allDay = false)
                app.core.actions.setSpan(t.id, at, at + 30 * 60_000L)
                close()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewOptionsSheet(options: CalendarViewOptions, close: () -> Unit, save: (CalendarViewOptions) -> Unit) {
    var o by remember { mutableStateOf(options) }
    fun set(n: CalendarViewOptions) { o = n; save(n) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("View options", style = MaterialTheme.typography.titleLarge)
            Text("Task colour", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = o.colorBy == "priority", onClick = { set(o.copy(colorBy = "priority")) }, label = { Text("By priority") })
                FilterChip(selected = o.colorBy == "list", onClick = { set(o.copy(colorBy = "list")) }, label = { Text("By list") })
            }
            Text("Style", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = o.itemStyle != "modern", onClick = { set(o.copy(itemStyle = "classic")) }, label = { Text("Classic") })
                FilterChip(selected = o.itemStyle == "modern", onClick = { set(o.copy(itemStyle = "modern")) }, label = { Text("Modern") })
            }
            Text("Show", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            OptionSwitch("Completed tasks", o.showCompleted) { set(o.copy(showCompleted = it)) }
            OptionSwitch("Checklist items", o.showChecklist) { set(o.copy(showChecklist = it)) }
            OptionSwitch("All repeat cycles", o.showRepeats) { set(o.copy(showRepeats = it)) }
            OptionSwitch("Habits", o.showHabits) { set(o.copy(showHabits = it)) }
            OptionSwitch("Focus records", o.showFocus) { set(o.copy(showFocus = it)) }
            OptionSwitch("Countdowns", o.showCountdowns) { set(o.copy(showCountdowns = it)) }
            OptionSwitch("Vietnamese lunar dates", o.showLunar) { set(o.copy(showLunar = it)) }
            Text("Synced with the Mac.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionSwitch(title: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = change)
    }
}
