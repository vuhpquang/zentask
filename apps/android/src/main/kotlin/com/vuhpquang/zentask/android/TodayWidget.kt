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
import androidx.glance.appwidget.updateAll
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
import com.vuhpquang.zentask.android.ui.dateText
import com.vuhpquang.zentask.android.ui.light
import com.vuhpquang.zentask.tasks.SmartList
import com.vuhpquang.zentask.tasks.TaskFilter
import com.vuhpquang.zentask.tasks.isOpen

/** One open task on the widget. */
private data class WidgetRow(val id: String, val title: String, val detail: String?, val overdue: Boolean)

/**
 * Home-screen widget: today's open tasks (the Today smart list, overdue included), tick to complete,
 * tap to open the app. Refreshed by [ZenApp] after every data change.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zen = (context.applicationContext as ZenTaskApplication).zen
        val first = load(context)
        provideContent {
            // The session stays alive between updates: reload on every data change, not just once.
            val revision by zen.revision.collectAsState()
            val rows by produceState(first, revision) { value = load(context) }
            GlanceTheme(colors = ColorProviders(light = light, dark = dark)) { Content(rows) }
        }
    }

    private suspend fun load(context: Context): List<WidgetRow> {
        val core = (context.applicationContext as ZenTaskApplication).zen.core
        val index = core.loadIndex()
        val filter = TaskFilter.Smart(SmartList.TODAY)
        val now = System.currentTimeMillis()
        return core.sections(index, filter, core.viewOptions(filter))
            .filter { !it.isCompleted }
            .flatMap { it.rows }
            .take(MAX_ROWS)
            .map { r ->
                val t = r.task
                val day = core.taskDayMillis(index, t)
                val due = t.dueDate ?: t.startDate
                WidgetRow(t.id, t.title.ifBlank { "Untitled" }, day?.let { dateText(it, t) }, overdue = due != null && due < now && !t.isAllDay)
            }
    }

    @Composable
    private fun Content(rows: List<WidgetRow>) {
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(20.dp).padding(12.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                Spacer(GlanceModifier.width(6.dp))
                Text(rows.size.toString(), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            }
            if (rows.isEmpty()) {
                Text(
                    "Nothing left for today",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(top = 12.dp).clickable(actionStartActivity<MainActivity>()),
                )
            } else {
                LazyColumn(GlanceModifier.fillMaxSize().padding(top = 6.dp)) {
                    items(rows, itemId = { it.id.hashCode().toLong() }) { row ->
                        Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(
                                checked = false,
                                onCheckedChange = actionRunCallback<CompleteTaskAction>(actionParametersOf(TASK_KEY to row.id)),
                            )
                            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>(actionParametersOf(OPEN_KEY to row.id)))) {
                                Text(row.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                                row.detail?.let {
                                    Text(it, maxLines = 1, style = TextStyle(color = if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_ROWS = 30
        val TASK_KEY = ActionParameters.Key<String>("taskId")
        /** Same name as [MainActivity.EXTRA_OPEN_TASK]: Glance passes start-activity parameters as extras. */
        val OPEN_KEY = ActionParameters.Key<String>(MainActivity.EXTRA_OPEN_TASK)

        suspend fun refresh(context: Context) = TodayWidget().updateAll(context)
    }
}

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TodayWidget.TASK_KEY] ?: return
        val zen = (context.applicationContext as ZenTaskApplication).zen
        zen.core.task(id)?.takeIf { it.isOpen && !it.deleted && it.trashedAt == null }?.let { zen.core.actions.complete(id) }
        TodayWidget().update(context, glanceId)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
