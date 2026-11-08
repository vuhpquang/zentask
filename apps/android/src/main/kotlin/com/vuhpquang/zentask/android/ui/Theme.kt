package com.vuhpquang.zentask.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** ZenTask's own palette: blue → violet (accent #5B5FE9 light / #8B8CFF dark), never TickTick's. */
object ZenColors {
    val accentLight = Color(0xFF5B5FE9)
    val accentDark = Color(0xFF8B8CFF)
    val violet = Color(0xFF8B5CF6)
    val high = Color(0xFFE5484D)
    val medium = Color(0xFFF59E0B)
    val low = Color(0xFF3B82F6)

    fun priority(p: Int, fallback: Color): Color = when (p) {
        5 -> high
        3 -> medium
        1 -> low
        else -> fallback
    }

    /** `#RRGGBB` (list/tag colours); null when absent or malformed. */
    fun hex(s: String?): Color? {
        val v = s?.trim()?.removePrefix("#") ?: return null
        if (v.length != 6) return null
        return v.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }
}

internal val light = lightColorScheme(
    primary = ZenColors.accentLight,
    secondary = ZenColors.violet,
    tertiary = Color(0xFF2E9E88),
    background = Color(0xFFF8F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F1F8),
)

internal val dark = darkColorScheme(
    primary = ZenColors.accentDark,
    secondary = Color(0xFFB59CFF),
    tertiary = Color(0xFF5CC8B0),
    background = Color(0xFF121218),
    surface = Color(0xFF17171F),
    surfaceContainer = Color(0xFF1F1F29),
)

@Composable
fun ZenTaskTheme(mode: String, content: @Composable () -> Unit) {
    val darkMode = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (darkMode) dark else light, content = content)
}
