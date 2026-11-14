package imbored.luna.station.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable

@Composable
fun WorkingScreen(step: String) {
    Page {
        Title("Luna")
        CircularProgressIndicator(color = LunaColors.Violet)
        Note(step, LunaColors.Text)
    }
}
