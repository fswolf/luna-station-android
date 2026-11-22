package imbored.luna.station.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imbored.luna.station.R
import imbored.luna.station.StationViewModel.Link
import imbored.luna.station.StationViewModel.Screen

/**
 * Settings: the connection to her PC, how this device behaves, and what the app is.
 * Opened from the emblem's menu; back (the arrow or the phone's back) returns to the chat.
 */
@Composable
fun SettingsScreen(
    s: Screen.Paired,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    startPortrait: Boolean,
    onStartPortrait: (Boolean) -> Unit,
    voice: Boolean,
    onVoice: (Boolean) -> Unit,
    roomStation: Boolean,
    onRoomStation: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(Modifier.fillMaxSize().background(LunaColors.Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = LunaColors.Text) }
            Text("Settings", color = LunaColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Connection") {
                val (dot, words) = when (val link = s.link) {
                    Link.Checking -> LunaColors.Muted to "Checking..."
                    Link.Connected -> LunaColors.Cyan to "Connected"
                    Link.Removed -> LunaColors.Pink to "This device was unpaired on the PC"
                    is Link.Down -> LunaColors.Pink to link.why
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Luna's PC", color = LunaColors.Text)
                        Text(s.host, color = LunaColors.Muted, fontSize = 13.sp)
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(words, color = LunaColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                }
                TextButton(onClick = onCheck, enabled = s.link != Link.Checking) { Text("Check again") }
                Text("Her certificate (SHA-256), to compare with the PC:", color = LunaColors.Muted, fontSize = 13.sp)
                Text(s.fingerprint, color = LunaColors.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                OutlinedButton(onClick = onForget) {
                    Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, tint = LunaColors.Pink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Forget this PC", color = LunaColors.Pink)
                }
            }
            Section("This device") {
                Text("Used as", color = LunaColors.Text)
                Row(Modifier.clip(RoundedCornerShape(50)).background(LunaColors.Background).padding(3.dp)) {
                    Choice("Room station", roomStation) { onRoomStation(true) }
                    Choice("Phone", !roomStation) { onRoomStation(false) }
                }
                Text(
                    if (roomStation) "Stays in one room. Her camera will be able to look when someone talks to it, so she knows who's there (coming soon)."
                    else "Goes with you. The camera is only used when you ask (camera features coming soon).",
                    color = LunaColors.Muted, fontSize = 13.sp,
                )
                Toggle("Play her voice", "Hear her answers here. Off: text only, and her portrait's mouth stays still.", voice, onVoice)
                Toggle("Keep the screen on", "For a station on a stand. Uses more battery.", keepAwake, onKeepAwake)
                Toggle("Open in Portrait", "Start on her portrait instead of the chat.", startPortrait, onStartPortrait)
            }
            Section("About") {
                Text("Luna Station $version", color = LunaColors.Text)
                Text(
                    "Open source (MIT). It only ever talks to the PC it paired with.",
                    color = LunaColors.Muted, fontSize = 13.sp,
                )
                // the one link out of the app: it opens your browser, the app itself never goes online
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fswolf/luna-station-android")))
                    }
                }) { Text("Source code on GitHub") }
            }
        }
    }
}

/** A titled card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = LunaColors.Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LunaColors.Surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** One half of a two-way choice, like the Chat | Portrait switch. */
@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) LunaColors.Background else LunaColors.Muted,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) LunaColors.Violet else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** One on/off setting: the whole row toggles it, not just the switch. */
@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LunaColors.Text)
            Text(detail, color = LunaColors.Muted, fontSize = 13.sp)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LunaColors.Background, checkedTrackColor = LunaColors.Violet,
                uncheckedThumbColor = LunaColors.Muted, uncheckedTrackColor = LunaColors.Background,
                uncheckedBorderColor = LunaColors.Outline,
            ),
        )
    }
}

