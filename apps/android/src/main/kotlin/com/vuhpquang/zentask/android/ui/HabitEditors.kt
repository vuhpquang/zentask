package com.vuhpquang.zentask.android.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.habits.HabitRow
import com.vuhpquang.zentask.model.Countdown
import com.vuhpquang.zentask.model.Habit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val PALETTE = listOf("#5B5FE9", "#8B5CF6", "#EC4899", "#EF4444", "#F59E0B", "#10B981", "#06B6D4", "#64748B")
private val DAY_CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/** The weekdays (MO…SU) of a `FREQ=WEEKLY;BYDAY=…` rule; empty for anything else. */
private fun ruleDays(rule: String?): Set<String> {
    val parts = rule.orEmpty().removePrefix("RRULE:").split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
    if (parts["FREQ"] != "WEEKLY" || parts.containsKey("TT_TIMES")) return emptySet()
    return parts["BYDAY"].orEmpty().split(',').map { it.trim().uppercase() }.filter { it in DAY_CODES }.toSet()
}

/** Every day: no rule, or FREQ=DAILY with INTERVAL 1 (no N-times-a-week). */
private fun isPlainDaily(rule: String?): Boolean {
    if (rule.isNullOrBlank()) return true
    val parts = rule.removePrefix("RRULE:").split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
    return parts["FREQ"] == "DAILY" && (parts["INTERVAL"]?.toIntOrNull() ?: 1) <= 1 && !parts.containsKey("TT_TIMES")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(selected: String, pick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (hex in PALETTE) {
            val c = ZenColors.hex(hex) ?: Color.Gray
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(c)
                    // A 0 dp border still draws a hairline in Compose: only add it when selected.
                    .then(if (hex.equals(selected, ignoreCase = true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { pick(hex) },
            )
        }
    }
}

/**
 * Create or edit a habit: name, colour, yes/no or an amount, which days, reminder times. A rule the editor
 * can't show (every 2 days, N times a week) is kept unless the days are changed here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitEditorDialog(app: ZenApp, editing: Habit?, close: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var color by remember { mutableStateOf(editing?.color ?: PALETTE[0]) }
    var amount by remember { mutableStateOf(editing?.type == Habit.TYPE_REAL) }
    var goal by remember { mutableStateOf(editing?.takeIf { it.type == Habit.TYPE_REAL }?.goal?.let(::fmtNumber) ?: "8") }
    var unit by remember { mutableStateOf(editing?.unit.orEmpty()) }
    val initialDays = ruleDays(editing?.repeatRule)
    val days = remember { mutableStateListOf<String>().apply { addAll(if (initialDays.isEmpty()) DAY_CODES else initialDays) } }
    var daysTouched by remember { mutableStateOf(false) }
    val reminders = remember { mutableStateListOf<String>().apply { addAll(editing?.reminders.orEmpty()) } }
    val goalValue = goal.replace(',', '.').toDoubleOrNull()
    val keepsOddRule = editing != null && initialDays.isEmpty() && !isPlainDaily(editing.repeatRule)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (editing == null) "New habit" else "Edit habit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ColorRow(color) { color = it }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !amount, onClick = { amount = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Yes / no") }
                    SegmentedButton(selected = amount, onClick = { amount = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Amount") }
                }
                if (amount) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(goal, { goal = it }, label = { Text("Goal a day") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                Text("Days", style = MaterialTheme.typography.labelLarge)
                if (keepsOddRule && !daysTouched) {
                    Text(editing?.let { app.core.habitRows().firstOrNull { r -> r.habit.id == it.id }?.schedule }.orEmpty() + " (pick days to change it)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (code in DAY_CODES) {
                        val label = DayOfWeek.entries[DAY_CODES.indexOf(code)].getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        FilterChip(selected = code in days && (!keepsOddRule || daysTouched), onClick = {
                            if (keepsOddRule && !daysTouched) days.clear()
                            daysTouched = true
                            if (code in days) { if (days.size > 1) days.remove(code) } else days.add(code)
                        }, label = { Text(label) })
                    }
                }
                Text("Reminders", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in reminders.sorted()) {
                        InputChip(selected = false, onClick = { reminders.remove(t) }, label = { Text(t) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove $t", Modifier.size(18.dp)) })
                    }
                    AssistChip(onClick = {
                        TimePickerDialog(context, { _, h, m ->
                            val t = "%02d:%02d".format(h, m)
                            if (t !in reminders) reminders.add(t)
                        }, 9, 0, true).show()
                    }, label = { Text("Add time") }, leadingIcon = { Icon(Icons.Filled.Notifications, null) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (!amount || (goalValue != null && goalValue > 0)), onClick = {
                val rule = when {
                    keepsOddRule && !daysTouched -> editing!!.repeatRule!!
                    days.size == 7 -> "RRULE:FREQ=DAILY;INTERVAL=1"
                    else -> "RRULE:FREQ=WEEKLY;BYDAY=" + DAY_CODES.filter { it in days }.joinToString(",")
                }
                val type = if (amount) Habit.TYPE_REAL else Habit.TYPE_BOOLEAN
                val g = if (amount) goalValue ?: 1.0 else 1.0
                val u = if (amount) unit.trim() else ""
                val a = app.core.habitActions
                val id = if (editing == null) {
                    a.create(name = name, icon = null, color = color, type = type, goal = g, step = 1.0, unit = u, repeatRule = rule).id
                } else {
                    a.update(editing, name = name, icon = editing.icon, color = color, type = type, goal = g, step = editing.step, unit = u, repeatRule = rule)
                    editing.id
                }
                if (reminders.sorted() != editing?.reminders.orEmpty()) a.setReminders(id, reminders.toList())
                close()
            }) { Text(if (editing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

fun fmtNumber(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

/** A habit's stats, this month's check-ins (tap a day to check in or undo) and Edit / Archive / Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailSheet(app: ZenApp, row: HabitRow, close: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val color = ZenColors.hex(row.habit.color) ?: MaterialTheme.colorScheme.primary
    val todayStamp = app.core.todayStamp()
    ModalBottomSheet(onDismissRequest = close, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(row.habit.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(row.schedule + if (row.habit.reminders.isNotEmpty()) " · " + row.habit.reminders.joinToString(", ") else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Current streak", "${row.stats.currentStreak}", Modifier.weight(1f))
                Stat("Longest", "${row.stats.longestStreak}", Modifier.weight(1f))
                Stat("Total days", "${row.stats.totalDays}", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›") }
            }
            MonthGrid(month, row.doneStamps, todayStamp, color) { stamp -> if (stamp <= todayStamp) app.core.habitCheckIn(row.habit.id, stamp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, null); Text(" Edit") }
                OutlinedButton(onClick = {
                    if (row.archived) app.core.habitActions.unarchive(row.habit.id) else app.core.habitActions.archive(row.habit.id)
                    close()
                }) { Icon(if (row.archived) Icons.Filled.Unarchive else Icons.Filled.Archive, null); Text(if (row.archived) " Restore" else " Archive") }
                OutlinedButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, null); Text(" Delete") }
            }
        }
    }
    if (editing) HabitEditorDialog(app, row.habit) { editing = false }
    if (confirmDelete) {
        Confirm(
            "Delete \"${row.habit.name}\"?", "The habit and all its check-ins are deleted on every device. Archive it instead to keep its history.",
            "Delete", onConfirm = { app.core.habitActions.delete(row.habit.id); confirmDelete = false; close() }, onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthGrid(month: YearMonth, done: Set<Int>, today: Int, color: Color, tap: (Int) -> Unit) {
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // Monday first
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            for (d in DayOfWeek.entries) Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (week in 0 until (cells + 6) / 7) {
            Row {
                for (col in 0 until 7) {
                    val dayNumber = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date: LocalDate = month.atDay(dayNumber)
                            val stamp = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
                            val isDone = stamp in done
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(if (isDone) color else Color.Transparent)
                                    .then(if (stamp == today) Modifier.border(2.dp, color, CircleShape) else Modifier)
                                    .clickable(enabled = stamp <= today) { tap(stamp) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$dayNumber", color = when {
                                    isDone -> Color.White
                                    stamp > today -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Create or edit a countdown; birthdays and anniversaries repeat every year. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CountdownEditorDialog(app: ZenApp, editing: Countdown?, close: () -> Unit) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var type by remember { mutableStateOf(editing?.type ?: "COUNTDOWN") }
    var color by remember { mutableStateOf(editing?.color ?: PALETTE[0]) }
    var showInCalendar by remember { mutableStateOf(editing?.showInCalendar ?: true) }
    var date by remember {
        mutableStateOf(editing?.date?.takeIf { it > 0 }?.let { LocalDate.of(it / 10_000, it / 100 % 100, it % 100) } ?: LocalDate.now().plusDays(7))
    }
    var pickingDate by remember { mutableStateOf(false) }
    val types = listOf("COUNTDOWN" to "Countdown", "BIRTHDAY" to "Birthday", "ANNIVERSARY" to "Anniversary", "HOLIDAY" to "Holiday")
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (editing == null) "New countdown" else "Edit countdown") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((t, label) in types) FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) })
                }
                ColorRow(color) { color = it }
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show in calendar", Modifier.weight(1f))
                    Switch(checked = showInCalendar, onCheckedChange = { showInCalendar = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val stamp = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
                val repeat = if (type == "COUNTDOWN") editing?.repeat?.takeIf { editing.type == "COUNTDOWN" } ?: "none" else "yearly"
                val a = app.core.countdownActions
                if (editing == null) {
                    a.create(name = name, date = stamp, type = type, repeat = repeat, color = color, icon = null, note = "", showInCalendar = showInCalendar)
                } else {
                    a.update(editing, name = name, date = stamp, type = type, repeat = repeat, color = color, icon = editing.icon, note = editing.note, showInCalendar = showInCalendar)
                }
                close()
            }) { Text(if (editing == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Long-press menu on a countdown card. */
@Composable
fun CountdownActionsDialog(app: ZenApp, countdown: Countdown, close: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (editing) {
        CountdownEditorDialog(app, countdown) { editing = false; close() }
        return
    }
    if (confirmDelete) {
        Confirm("Delete \"${countdown.name}\"?", "It's deleted on every device.", "Delete",
            onConfirm = { app.core.countdownActions.delete(countdown.id); close() }, onDismiss = { confirmDelete = false })
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(countdown.name) },
        text = {
            Column {
                TextButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, null); Text("  Edit") }
                TextButton(onClick = {
                    if (countdown.archivedAt == null) app.core.countdownActions.archive(countdown.id) else app.core.countdownActions.unarchive(countdown.id)
                    close()
                }) { Icon(Icons.Filled.Archive, null); Text(if (countdown.archivedAt == null) "  Archive" else "  Restore") }
                TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, null); Text("  Delete") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}
