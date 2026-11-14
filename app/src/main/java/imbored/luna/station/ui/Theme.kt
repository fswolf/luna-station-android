package imbored.luna.station.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Luna's terminal colours, the same as luna.imbored.me. Dark only, like her. */
object LunaColors {
    val Background = Color(0xFF14111F)
    val Surface = Color(0xFF1E1A2E)
    val Pink = Color(0xFFFF87D7)
    val Violet = Color(0xFFB48CFF)
    val Cyan = Color(0xFF5FD7FF)
    val Text = Color(0xFFE8E3F5)
    val Muted = Color(0xFF9A93B0)
}

private val scheme = darkColorScheme(
    primary = LunaColors.Pink,
    secondary = LunaColors.Violet,
    tertiary = LunaColors.Cyan,
    background = LunaColors.Background,
    surface = LunaColors.Surface,
    onPrimary = LunaColors.Background,
    onSecondary = LunaColors.Background,
    onBackground = LunaColors.Text,
    onSurface = LunaColors.Text,
)

@Composable
fun LunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
