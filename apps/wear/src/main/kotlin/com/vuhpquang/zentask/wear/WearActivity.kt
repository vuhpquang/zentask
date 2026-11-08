package com.vuhpquang.zentask.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.vuhpquang.zentask.habits.HabitRow
import kotlinx.coroutines.launch

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val zen = (application as WearApplication).zen
        setContent {
            // ZenTask's dark accent; watch faces are always dark.
            MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFF8B8CFF), onPrimary = Color(0xFF14143A))) {
                AppScaffold { TodayScreen(zen) }
            }
        }
    }
}

@Composable
private fun TodayScreen(zen: WearZen) {
    val today by zen.today.collectAsState()
    val signedIn by zen.signedIn.collectAsState()
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) { padding ->
        ScalingLazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Today") } }
            val tasks = today?.tasks.orEmpty()
            if (today != null && tasks.isEmpty()) {
                item { Text("Nothing left for today", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            for (t in tasks) {
                item(key = t.id) {
                    CheckboxButton(
                        checked = false,
                        onCheckedChange = { zen.complete(t.id) },
                        label = { Text(t.title.ifBlank { "Untitled" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val habits = today?.habits.orEmpty()
            if (habits.isNotEmpty()) {
                item { ListHeader { Text("Habits") } }
                for (h in habits) item(key = "h_" + h.habit.id) { HabitButton(zen, h) }
            }
            item { ListHeader { Text(if (signedIn) "Account" else "Sign in to sync") } }
            if (signedIn) {
                item { FilledTonalButton(onClick = { zen.syncNow() }, modifier = Modifier.fillMaxWidth(), label = { Text("Sync now") }) }
                item { FilledTonalButton(onClick = { zen.signOut() }, modifier = Modifier.fillMaxWidth(), label = { Text("Sign out") }) }
            } else {
                item { SignIn(zen) }
            }
        }
    }
}

@Composable
private fun HabitButton(zen: WearZen, row: HabitRow) {
    val numeric = row.habit.goal > 1.0
    CheckboxButton(
        checked = row.doneToday,
        onCheckedChange = { zen.checkIn(row.habit.id) },
        label = { Text(row.habit.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (numeric) ({ Text("${fmt(row.todayValue)} / ${fmt(row.habit.goal)} ${row.habit.unit}".trim()) }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun SignIn(zen: WearZen) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(12.dp),
        ) {
            if (password.isEmpty()) Text("Server password", color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = password, onValueChange = { password = it; error = null },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        Button(
            onClick = {
                busy = true
                scope.launch { error = zen.signIn(password); busy = false; if (error == null) password = "" }
            },
            enabled = password.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text(if (busy) "Signing in…" else "Sign in") },
        )
    }
}
