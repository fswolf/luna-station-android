package imbored.luna.station.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The centred column every simple screen uses, scrollable for small phones. */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LunaColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Her mark, the launcher icon drawn live: a lavender crescent with her pink dot. */
@Composable
fun Crescent(size: Dp = 96.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(LunaColors.Violet, r)
        drawCircle(LunaColors.Background, r * 0.84f, center + Offset(r * 0.40f, -r * 0.22f))
        drawCircle(LunaColors.Pink, r * 0.13f, center + Offset(r * 0.50f, r * 0.48f))
    }
}

@Composable
fun Title(text: String) = Text(text, color = LunaColors.Pink, fontSize = 44.sp, fontWeight = FontWeight.Bold)

@Composable
fun Note(text: String, color: androidx.compose.ui.graphics.Color = LunaColors.Muted) =
    Text(text, color = color, textAlign = TextAlign.Center)
