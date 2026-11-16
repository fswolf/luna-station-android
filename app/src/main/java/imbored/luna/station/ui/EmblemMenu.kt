package imbored.luna.station.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imbored.luna.station.R
import imbored.luna.station.StationViewModel.Link
import imbored.luna.station.StationViewModel.Screen

/**
 * The menu under her emblem: where she is and whether she's there, then Settings, Forget this
 * PC and Exit. (The address used to sit at the top right; it lives here now.)
 */
@Composable
internal fun EmblemMenu(s: Screen.Paired, onSettings: () -> Unit, onForget: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    val (dot, words) = when (s.link) {
        Link.Checking -> LunaColors.Muted to "checking"
        Link.Connected -> LunaColors.Cyan to "connected"
        Link.Removed -> LunaColors.Pink to "unpaired"
        is Link.Down -> LunaColors.Pink to "can't reach her"
    }
    Box {
        Emblem(Modifier.clip(CircleShape).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LunaColors.Surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Luna", color = LunaColors.Pink, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Text("  ${s.host} · $words", color = LunaColors.Muted, fontSize = 13.sp)
                }
            }
            HorizontalDivider(color = LunaColors.Outline)
            MenuItem(R.drawable.ic_settings, "Settings") { open = false; onSettings() }
            MenuItem(R.drawable.ic_link_off, "Forget this PC", LunaColors.Pink) { open = false; onForget() }
            HorizontalDivider(color = LunaColors.Outline)
            // a room device has no back stack worth keeping: Exit closes it outright
            MenuItem(R.drawable.ic_exit, "Exit") { open = false; activity?.finishAndRemoveTask() }
        }
    }
}

@Composable
private fun MenuItem(icon: Int, label: String, color: Color = LunaColors.Text, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** Her emblem (icons/luna-emblem.png, as drawable-nodpi/luna_emblem.png) in a white circle. */
@Composable
internal fun Emblem(modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(LunaColors.IconWhite),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.luna_emblem), contentDescription = "Menu", modifier = Modifier.size((size * 0.78f).dp))
    }
}

// The first emblem, drawn in code (replaced by the real one above):
// /**
//  * Her emblem, top left: a crescent with a prompt in it and her pink dot (the same mark as her
//  * site), on a small tile. Tapping it opens the menu: Settings (today: the connection and her
//  * certificate), Forget this PC, and Exit.
//  */
// @Composable
// internal fun EmblemMenu(onSettings: () -> Unit, onForget: () -> Unit) {
//     var open by rememberSaveable { mutableStateOf(false) }
//     val activity = LocalContext.current as? Activity
//     Box {
//         Emblem(Modifier.clickable { open = true })
//         DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LunaColors.Surface) {
//             DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() })
//             DropdownMenuItem(text = { Text("Forget this PC") }, onClick = { open = false; onForget() })
//             // a room device has no back stack worth keeping: Exit closes it outright
//             DropdownMenuItem(text = { Text("Exit") }, onClick = { open = false; activity?.finishAndRemoveTask() })
//         }
//     }
// }
//
// /** The emblem itself, drawn live so it stays sharp at any size. */
// @Composable
// internal fun Emblem(modifier: Modifier = Modifier, size: Int = 40) {
//     Box(
//         modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(LunaColors.Surface),
//         contentAlignment = Alignment.Center,
//     ) {
//         Canvas(Modifier.size((size * 0.78f).dp)) {
//             val r = this.size.minDimension / 2f
//             drawCircle(LunaColors.Text, r)                                                    // the moon...
//             drawCircle(LunaColors.Surface, r * 0.80f, center + Offset(r * 0.36f, -r * 0.30f)) // ...bitten into a crescent
//             drawCircle(LunaColors.Pink, r * 0.13f, center + Offset(r * 0.78f, -r * 0.74f))    // her dot
//         }
//         Text(
//             ">_", color = LunaColors.Violet, fontSize = (size * 0.26f).sp, fontWeight = FontWeight.Bold,
//             fontFamily = FontFamily.Monospace,
//             modifier = Modifier.offset(x = (size * 0.12f).dp, y = (size * 0.02f).dp),   // in the crescent's hollow
//         )
//     }
// }
