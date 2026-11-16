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
    val Mine = Color(0xFF352A52)      // your bubbles: surface with a little more violet in it
    val Outline = Color(0xFF3A2D55)   // field borders and dividers, the site's panel border
    val IconWhite = Color(0xFFFBF8F3) // behind her emblem: the launcher icon's warm white (values/luna_icon_colors.xml)
}

// Violet is the colour of doing things (buttons, the mic, the switch), pink is Luna herself
// (her name, Stop, anything that needs attention). Outline on the fields so they sit quietly
// until focused.
private val scheme = darkColorScheme(
    primary = LunaColors.Violet,
    secondary = LunaColors.Pink,
    tertiary = LunaColors.Cyan,
    background = LunaColors.Background,
    surface = LunaColors.Surface,
    surfaceVariant = LunaColors.Surface,
    outline = LunaColors.Outline,
    onPrimary = LunaColors.Background,
    onSecondary = LunaColors.Background,
    onBackground = LunaColors.Text,
    onSurface = LunaColors.Text,
    onSurfaceVariant = LunaColors.Muted,
)

@Composable
fun LunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
