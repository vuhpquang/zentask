package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Author identity shared by all of the owner's apps: copied verbatim from app-shell's `Author`
 * (github.com/vuhpquang/app-shell, Sources/AppShellCore/Author.swift) — hub rule `.claude/rules/app-shell.md`.
 */
object Author {
    const val NAME = "Ha Phung Quang Vu"
    const val WEBSITE = "https://vuhpquang.com"
    const val GITHUB = "https://github.com/vuhpquang"
    const val LINKEDIN = "https://www.linkedin.com/in/vuhpquang/"

    /** "© 2026 Ha Phung Quang Vu", or "© 2025–2026 …" when the first year is older. */
    fun copyright(firstYear: Int, year: Int = Calendar.getInstance().get(Calendar.YEAR)): String =
        "© ${if (firstYear < year) "$firstYear–$year" else "$year"} $NAME"
}

private const val REPO = "https://github.com/vuhpquang/zentask"
private const val FIRST_YEAR = 2026

/** Settings → About (last section): who makes ZenTask, the links, Report an issue, copyright. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutSection(version: String) {
    val uri = LocalUriHandler.current
    Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text("ZenTask $version", fontWeight = FontWeight.SemiBold)
    Text("Your tasks, habits and calendar, calm and in one place.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Made by ${Author.NAME}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { uri.openUri(Author.WEBSITE) }) { Text("vuhpquang.com") }
        TextButton(onClick = { uri.openUri(Author.GITHUB) }) { Text("GitHub") }
        TextButton(onClick = { uri.openUri(Author.LINKEDIN) }) { Text("LinkedIn") }
        TextButton(onClick = { uri.openUri(REPO) }) { Text("Source code") }
    }
    OutlinedButton(onClick = { uri.openUri("$REPO/issues/new") }) { Text("Report an issue") }
    Text(Author.copyright(FIRST_YEAR), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
