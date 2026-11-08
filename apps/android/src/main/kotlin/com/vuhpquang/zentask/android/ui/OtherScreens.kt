@file:OptIn(ExperimentalMaterial3Api::class)

package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.calendar.CalendarEntry
import com.vuhpquang.zentask.focus.PomodoroPlan
import com.vuhpquang.zentask.model.FocusRecord
import com.vuhpquang.zentask.model.Habit
import com.vuhpquang.zentask.sync.SyncStatus
import com.vuhpquang.zentask.tasks.isDone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun midnight(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

// MARK: Calendar

/** A week strip to pick a day, then that day's events, tasks, habits and countdowns in time order. */
@Composable
fun CalendarScreen(app: ZenApp) {
    val revision by app.revision.collectAsState()
    var day by rememberSaveable { mutableStateOf(LocalDate.now(zone).toEpochDay()) }
    val selected = LocalDate.ofEpochDay(day)
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    val week = loaded(revision, monday) { app.core.calendarDays(midnight(monday), 7) } ?: return
    val today = LocalDate.now(zone)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(selected.format(DateTimeFormatter.ofPattern("MMMM yyyy"))) },
            actions = { OutlinedButton(onClick = { day = today.toEpochDay() }) { Text("Today") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { day -= 7 }) { Text("‹", fontSize = 22.sp) }
                for (i in 0 until 7) {
                    val d = monday.plusDays(i.toLong())
                    val isSel = d == selected
                    val busy = week.getOrNull(i)?.let { it.allDay.isNotEmpty() || it.timed.isNotEmpty() } == true
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { day = d.toEpochDay() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val fg = if (isSel) MaterialTheme.colorScheme.onPrimary else if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        Text(d.format(DateTimeFormatter.ofPattern("EEEEE")), style = MaterialTheme.typography.labelSmall, color = fg)
                        Text("${d.dayOfMonth}", fontWeight = FontWeight.SemiBold, color = fg)
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (busy) fg else Color.Transparent))
                    }
                }
                IconButton(onClick = { day += 7 }) { Text("›", fontSize = 22.sp) }
            }
            val entries = week.getOrNull((selected.toEpochDay() - monday.toEpochDay()).toInt())?.let { it.allDay + it.timed.map { p -> p.entry } }.orEmpty()
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing planned", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(entries, key = { it.id }) { AgendaCard(app, it) }
                }
            }
        }
    }
}

@Composable
private fun AgendaCard(app: ZenApp, e: CalendarEntry) {
    val color = when (e.kind) {
        "task" -> ZenColors.hex(e.color) ?: ZenColors.priority(e.priority, MaterialTheme.colorScheme.primary)
        else -> ZenColors.hex(e.color) ?: MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (e.allDay) "All day" else hm(e.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f))
                .clickable(enabled = e.kind == "task" || e.kind == "habit") {
                    when (e.kind) {
                        "task" -> app.core.task(e.id)?.let { toggleComplete(app, it) }
                        "habit" -> app.core.toggleHabitEntry(e.id)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(44.dp).background(color))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!e.allDay && e.kind == "event") Text("${hm(e.start)} – ${hm(e.end)}", style = MaterialTheme.typography.labelSmall, color = color)
                Text(
                    (if (e.done) "✓ " else "") + e.title.ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: Habits

@Composable
fun HabitsScreen(app: ZenApp) {
    val revision by app.revision.collectAsState()
    val rows = loaded(revision) { app.core.habitRows().filter { !it.archived } } ?: emptyList()
    val week = loaded(revision) { app.core.habitWeek() } ?: emptyList()
    val today = app.core.todayStamp()
    Scaffold(topBar = { TopAppBar(title = { Text("Habits") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (d in week) {
                        val date = LocalDate.of(d.stamp / 10_000, d.stamp / 100 % 100, d.stamp % 100)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${date.dayOfMonth}", fontWeight = if (d.stamp == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (d.stamp == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Ring(if (d.due > 0) d.done.toFloat() / d.due else 0f, Modifier.size(26.dp))
                        }
                    }
                }
            }
            if (rows.isEmpty()) item { Text("No habits yet. Habits from TickTick and the Mac app show up here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
            items(rows, key = { it.habit.id }) { row ->
                val color = ZenColors.hex(row.habit.color) ?: MaterialTheme.colorScheme.primary
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                            Text(row.habit.icon?.takeIf { it.length <= 2 } ?: row.habit.name.take(1), color = color, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.habit.name, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocalFireDepartment, null, tint = ZenColors.medium, modifier = Modifier.size(14.dp))
                                Text(" ${row.stats.currentStreak} ${row.stats.unit} · ${row.schedule}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.habit.type == Habit.TYPE_REAL) {
                                Text("${fmt(row.todayValue)} / ${fmt(row.habit.goal)} ${row.habit.unit}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val done = row.doneToday
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (done) color else Color.Transparent)
                                .clickable { app.core.habitCheckIn(row.habit.id, today) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) Icon(Icons.Filled.Check, "Done today", tint = Color.White)
                            else Ring(if (row.habit.type == Habit.TYPE_REAL && row.habit.goal > 0) (row.todayValue / row.habit.goal).toFloat() else 0f, Modifier.size(34.dp), color)
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun Ring(progress: Float, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// MARK: Countdown

@Composable
fun CountdownScreen(app: ZenApp) {
    val revision by app.revision.collectAsState()
    val cards = loaded(revision) { app.core.countdownCards().filter { it.countdown.archivedAt == null } } ?: return
    Scaffold(topBar = { TopAppBar(title = { Text("Countdown") }) }) { padding ->
        if (cards.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No countdowns yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Scaffold
        }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.padding(padding).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.countdown.id }) { card ->
                val color = ZenColors.hex(card.countdown.color) ?: MaterialTheme.colorScheme.primary
                val date = Instant.ofEpochMilli(card.dateMillis).atZone(zone).toLocalDate()
                Column(
                    Modifier.aspectRatio(1.1f).clip(RoundedCornerShape(16.dp))
                        .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.75f).compositeOver(Color.Black)))).padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(card.countdown.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (card.daysLeft != 0) Text("${kotlin.math.abs(card.daysLeft)}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when { card.daysLeft == 0 -> "Today"; card.daysLeft > 0 -> " days left"; else -> " days ago" },
                            color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp),
                            fontSize = if (card.daysLeft == 0) 32.sp else 14.sp,
                        )
                    }
                    Text(date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) + if (card.years > 0) " · ${card.years}" else "", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

// MARK: Focus

/** Pomodoro (25 minutes, then a break) or a stopwatch; finished sessions are saved and synced. */
@Composable
fun FocusScreen(app: ZenApp) {
    val revision by app.revision.collectAsState()
    val stats = loaded(revision) { app.core.focusStats() } ?: return
    val stopwatch by app.focusStopwatch.collectAsState()
    val startedAt by app.focusStartedAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = startedAt > 0
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = if (running) ((now - startedAt) / 1000).coerceAtLeast(0) else 0
    val length = app.pomoMillis / 1000
    Scaffold(topBar = { TopAppBar(title = { Text("Focus") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.padding(vertical = 8.dp)) {
                    SegmentedButton(selected = !stopwatch, onClick = { app.setFocusStopwatch(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Pomo") }
                    SegmentedButton(selected = stopwatch, onClick = { app.setFocusStopwatch(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Stopwatch") }
                }
                Box(Modifier.size(260.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                    Ring(if (stopwatch) (if (running) 1f else 0f) else (elapsed.toFloat() / length).coerceAtMost(1f), Modifier.fillMaxSize())
                    val shown = if (stopwatch) elapsed else (length - elapsed).coerceAtLeast(0)
                    Text("%02d:%02d".format(shown / 60, shown % 60), fontSize = 52.sp, fontWeight = FontWeight.Light)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!running) {
                        Button(onClick = { now = System.currentTimeMillis(); app.startFocus() }) { Text("Start") }
                    } else {
                        OutlinedButton(onClick = { app.stopFocus() }) { Text(if (stopwatch) "Stop and save" else "Stop") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Today's pomos", "${stats.todayPomos}", Modifier.weight(1f))
                    StatCard("Today's focus", "${stats.todayMinutes}m", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("Total pomos", "${stats.totalPomos}", Modifier.weight(1f))
                    StatCard("Total focus", "${stats.totalMinutes / 60}h ${stats.totalMinutes % 60}m", Modifier.weight(1f))
                }
            }
            for (d in stats.days.take(14)) {
                item(key = d.date.toString()) {
                    Text(d.date.toString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp))
                }
                items(d.records, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${hm(r.start)} – ${hm(r.end)}", Modifier.weight(1f))
                        Text("${r.minutes}m", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

// MARK: Settings

@Composable
fun SettingsScreen(app: ZenApp, close: () -> Unit) {
    val signedIn by app.signedIn.collectAsState()
    val status by app.syncStatus.collectAsState()
    val mode by app.themeMode.collectAsState()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(onBack = close)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            if (signedIn) {
                Text(syncText(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { app.syncNow() }) { Icon(Icons.Filled.Refresh, null); Text(" Sync now") }
                    OutlinedButton(onClick = { app.signOut() }) { Text("Sign out") }
                }
            } else {
                Text("Sign in with your ZenTask server password to sync with the Mac.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = password.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch { error = app.signIn(password); busy = false; if (error == null) password = "" }
                }) { Text(if (busy) "Signing in…" else "Sign in") }
            }
            Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for ((key, label) in listOf("system" to "System", "light" to "Light", "dark" to "Dark")) {
                    FilterChip(selected = mode == key, onClick = { app.setThemeMode(key) }, label = { Text(label) })
                }
            }
        }
    }
}

fun syncText(status: SyncStatus): String = when (status) {
    is SyncStatus.Idle -> "Waiting to sync"
    is SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Synced -> "Synced " + hm(status.at)
    is SyncStatus.Failed -> "Sync failed: ${status.message}"
    else -> "Sync"
}

// MARK: Search

@Composable
fun SearchScreen(app: ZenApp, open: (String) -> Unit, close: () -> Unit) {
    val index by app.index.collectAsState()
    var query by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler(onBack = close)
    val fold = { s: String -> java.text.Normalizer.normalize(s.replace("đ", "d").replace("Đ", "d"), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase() }
    val q = fold(query.trim())
    val hits = remember(index, q) {
        if (q.isEmpty()) emptyList() else index?.allTasks.orEmpty().filter { it.trashedAt == null && (fold(it.title).contains(q) || fold(it.content).contains(q)) }.take(80)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { TextField(query, { query = it }, placeholder = { Text("Search tasks and notes") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(hits, key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable { open(t.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(t.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listName(index, t.projectId) + if (t.isDone) " · completed" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Reads data off the main thread whenever [keys] change (a data revision, a week…), keeping the previous
 * value on screen while the next one loads. Null only before the first load.
 */
@Composable
private fun <T> loaded(vararg keys: Any?, load: () -> T): T? =
    androidx.compose.runtime.produceState<T?>(null, *keys) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { load() }
    }.value
