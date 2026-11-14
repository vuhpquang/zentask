package com.vuhpquang.zentask.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vuhpquang.zentask.android.ui.dark
import com.vuhpquang.zentask.android.ui.light

private data class HabitItem(val id: String, val name: String, val done: Boolean, val detail: String)

/** Today's habits: tick to check in (yes/no) or add one step (amounts); tap a name to open the app. */
class HabitsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zen = (context.applicationContext as ZenTaskApplication).zen
        val first = load(context)
        provideContent {
            val revision by zen.revision.collectAsState()
            val rows by produceState(first, revision) { value = load(context) }
            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) { Content(rows) }
        }
    }

    private fun load(context: Context): List<HabitItem> {
        val core = (context.applicationContext as ZenTaskApplication).zen.core
        return core.habitRows().filter { !it.archived && (it.dueToday || it.doneToday) }.map { r ->
            val amount = r.habit.type == com.vuhpquang.zentask.model.Habit.TYPE_REAL
            HabitItem(r.habit.id, r.habit.name.ifBlank { "Habit" }, r.doneToday,
                if (amount) "${com.vuhpquang.zentask.android.ui.fmtNumber(r.todayValue)} / ${com.vuhpquang.zentask.android.ui.fmtNumber(r.habit.goal)} ${r.habit.unit}".trim() else r.schedule)
        }
    }

    @Composable
    private fun Content(rows: List<HabitItem>) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Habits", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                Spacer(GlanceModifier.width(6.dp))
                Text("${rows.count { it.done }}/${rows.size}", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            }
            if (rows.isEmpty()) {
                Text("No habits today", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(top = 12.dp).clickable(actionStartActivity<MainActivity>()))
            } else {
                LazyColumn(GlanceModifier.fillMaxSize().padding(top = 6.dp)) {
                    items(rows, itemId = { it.id.hashCode().toLong() }) { row ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(checked = row.done, onCheckedChange = actionRunCallback<CheckInHabitAction>(actionParametersOf(HABIT_KEY to row.id)))
                            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                                Text(row.name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                                Text(row.detail, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        val HABIT_KEY = ActionParameters.Key<String>("habitId")
    }
}

class CheckInHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[HabitsWidget.HABIT_KEY] ?: return
        val core = (context.applicationContext as ZenTaskApplication).zen.core
        core.habitCheckIn(id, core.todayStamp())
        TodayWidget.refresh(context)
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitsWidget()
}

private data class CountdownItem(val name: String, val days: Int, val date: String)

/** The nearest countdowns, big number first. */
class CountdownWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zen = (context.applicationContext as ZenTaskApplication).zen
        val first = load(context)
        provideContent {
            val revision by zen.revision.collectAsState()
            val rows by produceState(first, revision) { value = load(context) }
            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) { Content(rows) }
        }
    }

    private fun load(context: Context): List<CountdownItem> {
        val core = (context.applicationContext as ZenTaskApplication).zen.core
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM")
        return core.countdownCards().filter { it.countdown.archivedAt == null && it.daysLeft >= 0 }.sortedBy { it.daysLeft }.take(6).map { c ->
            val date = java.time.Instant.ofEpochMilli(c.dateMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            CountdownItem(c.countdown.name.ifBlank { "Countdown" }, c.daysLeft, date.format(fmt))
        }
    }

    @Composable
    private fun Content(rows: List<CountdownItem>) {
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(12.dp).clickable(actionStartActivity<MainActivity>())) {
            val next = rows.firstOrNull()
            if (next == null) {
                Text("No countdowns", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
                return@Column
            }
            Text(next.name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (next.days == 0) "Today" else "${next.days}", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 34.sp))
                if (next.days > 0) Text(if (next.days == 1) " day" else " days", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
            }
            Text(next.date, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            for (r in rows.drop(1)) {
                Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(r.name, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp), modifier = GlanceModifier.defaultWeight())
                    Text(if (r.days == 0) "today" else "${r.days}d", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                }
            }
        }
    }
}

class CountdownWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CountdownWidget()
}
