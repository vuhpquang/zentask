package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.tasks.ProductivityStats

/** Drawer → Statistics: completed per day (30 days), this week vs last, streaks, lists, focus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(app: ZenApp, close: () -> Unit) {
    val index by app.index.collectAsState()
    val stats = loaded(index) { index?.let { app.core.productivity(it) } }
    androidx.activity.compose.BackHandler(onBack = close)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Statistics") }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        val s = stats ?: return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("This week", "${s.thisWeek}", if (s.lastWeek == 0) "Last week 0" else "Last week ${s.lastWeek}", Modifier.weight(1f))
                Tile("Streak", "${s.streak} d", "Best ${s.bestStreak}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("Last 30 days", "${s.total}", s.best?.let { "Best day: ${it.done}" } ?: "", Modifier.weight(1f))
                val m = s.focusMinutesThisWeek
                Tile("Focus this week", if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m", "", Modifier.weight(1f))
            }
            Card("Completed per day") { Bars(s) }
            if (s.byList.isNotEmpty()) Card("By list (30 days)") {
                val top = s.byList.maxOf { it.done }.coerceAtLeast(1)
                for (l in s.byList) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.width(110.dp), maxLines = 1)
                        Box(Modifier.weight(1f)) {
                            Box(Modifier.fillMaxWidth(l.done.toFloat() / top).height(12.dp).clip(RoundedCornerShape(3.dp))
                                .background(ZenColors.hex(l.color) ?: MaterialTheme.colorScheme.primary))
                        }
                        Text("  ${l.done}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bars(s: ProductivityStats) {
    val top = s.days.maxOf { it.done }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (d in s.days) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                if (d.done > 0) Box(Modifier.fillMaxWidth().fillMaxHeight(d.done.toFloat() / top).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(MaterialTheme.colorScheme.primary))
            }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("30 days ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tile(title: String, value: String, note: String, modifier: Modifier) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(note.ifEmpty { " " }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}
