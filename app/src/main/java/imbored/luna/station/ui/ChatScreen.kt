package imbored.luna.station.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import imbored.luna.station.R
import imbored.luna.station.StationViewModel.Chat
import imbored.luna.station.StationViewModel.Link
import imbored.luna.station.StationViewModel.Message
import imbored.luna.station.StationViewModel.Screen

/** Everything a paired station needs to do the conversation, without the view model itself. */
class ChatActions(
    val send: (String) -> Unit,
    val talk: () -> Unit,
    val stop: () -> Unit,
    val answer: (Boolean) -> Unit,
    val check: () -> Unit,
    val forget: () -> Unit,
    val leave: () -> Unit,
    val pairing: () -> imbored.luna.station.station.Pairing?,
)

/** The conversation, like a messaging app: your words on the right, hers on the left. */
@Composable
fun ChatScreen(s: Screen.Paired, chat: Chat, act: ChatActions) {
    // Back in front: is she still there? Going away: close the mic.
    LifecycleResumeEffect(Unit) {
        act.check()
        onPauseOrDispose { act.leave() }
    }
    var text by rememberSaveable { mutableStateOf("") }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var portrait by rememberSaveable { mutableStateOf(false) }   // Chat | Portrait: both share the one turn
    var micNote by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) act.talk()
        else micNote = "Luna isn't allowed to use the microphone. Allow it in Settings, Apps, Luna, Permissions."
    }
    val mic = {
        micNote = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) act.talk() else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val list = rememberLazyListState()
    // Newest at the bottom; the list is reversed, so item 0 is the newest. Follow it only
    // when you're already there, so reading back isn't yanked away. Your own message always jumps.
    val last = chat.messages.lastOrNull()
    LaunchedEffect(chat.messages.size) {
        if (last?.mine == true || list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }

    // see-through while the portrait shows: her WebView is under this Compose tree (MainActivity),
    // and the activity's own background is the same colour
    val fill = if (portrait) Modifier else Modifier.background(LunaColors.Background)
    Column(Modifier.fillMaxSize().then(fill).safeDrawingPadding()) {
        Header(s, portrait, onSwitch = { portrait = it }, onInfo = { showInfo = true })
        val pairing = act.pairing()
        if (portrait && pairing != null) {
            PortraitPane(pairing, chat, onMic = mic, onStop = act.stop, onAnswer = act.answer, note = chat.note ?: micNote)
        } else {
            LazyColumn(
                state = list,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chat.messages.asReversed(), key = { it.id }) { Bubble(it) }
            }
            (chat.note ?: micNote)?.let { Line(it, LunaColors.Pink) }
            if (chat.status.isNotEmpty()) Line(chat.status, LunaColors.Muted, dot = LunaColors.Violet)
            chat.ask?.let { AskCard(it, act.answer) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicButton(chat, onClick = mic, size = 48)
                val sendNow = { if (!chat.busy && text.isNotBlank()) { act.send(text); text = "" } }
                // a quiet pill: outline until it's focused, never a loud border
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },          // Luna's limit for typed turns
                    placeholder = { Text("Say something", color = LunaColors.Muted) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LunaColors.Violet,
                        unfocusedBorderColor = LunaColors.Outline,
                        disabledBorderColor = LunaColors.Outline,
                        focusedContainerColor = LunaColors.Surface,
                        unfocusedContainerColor = LunaColors.Surface,
                        disabledContainerColor = LunaColors.Surface,
                        cursorColor = LunaColors.Pink,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendNow() }),
                    enabled = !chat.recording,
                )
                if (chat.busy) {
                    Button(onClick = act.stop, colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Pink)) { Text("Stop") }
                } else {
                    Button(onClick = sendNow, enabled = text.isNotBlank() && !chat.recording) { Text("Send") }
                }
            }
        }
    }

    if (showInfo) InfoDialog(s, onClose = { showInfo = false }, onCheck = act.check, onForget = { showInfo = false; act.forget() })
}

@Composable
private fun Header(s: Screen.Paired, portrait: Boolean, onSwitch: (Boolean) -> Unit, onInfo: () -> Unit) {
    val (dot, words) = when (s.link) {
        Link.Checking -> LunaColors.Muted to "checking"
        Link.Connected -> LunaColors.Cyan to s.host
        Link.Removed -> LunaColors.Pink to "unpaired"
        is Link.Down -> LunaColors.Pink to "can't reach"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // tapping the name or the status opens the info box (connection, certificate, Forget)
        Text("Luna", color = LunaColors.Pink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onInfo))
        // the switch sits in a track, so the two halves read as one control
        Row(Modifier.clip(RoundedCornerShape(50)).background(LunaColors.Surface).padding(3.dp)) {
            Switch2("Chat", !portrait) { onSwitch(false) }
            Switch2("Portrait", portrait) { onSwitch(true) }
        }
        // the status gives way first on a narrow screen, never the switch
        Row(
            Modifier.weight(1f).clickable(onClick = onInfo),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(
                " $words", color = LunaColors.Muted, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
    if (s.link is Link.Removed || s.link is Link.Down) {
        Line((s.link as? Link.Down)?.why ?: "This device was unpaired on the PC. Tap Luna above to forget it and pair again.", LunaColors.Pink)
    }
}

/** One half of the Chat | Portrait switch. */
@Composable
private fun Switch2(label: String, on: Boolean, onClick: () -> Unit) {
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

/** The talk button. While the mic is open a pink ring breathes out from it, so you can see she's listening. */
@Composable
private fun MicButton(chat: Chat, onClick: () -> Unit, size: Int) {
    Box(contentAlignment = Alignment.Center) {
        if (chat.recording) {
            val pulse = rememberInfiniteTransition(label = "mic")
            val grow by pulse.animateFloat(
                initialValue = 1f, targetValue = 1.5f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "ring",
            )
            Box(
                Modifier.size(size.dp)
                    .graphicsLayer { scaleX = grow; scaleY = grow; alpha = (1.5f - grow) * 1.2f }
                    .clip(CircleShape).background(LunaColors.Pink),
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = !chat.busy,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (chat.recording) LunaColors.Pink else LunaColors.Violet,
                contentColor = LunaColors.Background,
                disabledContainerColor = LunaColors.Surface,
                disabledContentColor = LunaColors.Muted,
            ),
            modifier = Modifier.size(size.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = if (chat.recording) "Send what you said" else "Talk",
                modifier = Modifier.size((size / 2).dp),
            )
        }
    }
}

/**
 * The portrait full size, a big talk button, and her words as a subtitle. Same turn as the
 * chat: switching views mid-answer doesn't interrupt her.
 */
@Composable
private fun PortraitPane(
    p: imbored.luna.station.station.Pairing,
    chat: Chat,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    note: String?,
) {
    val last = chat.messages.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        PortraitView(p, Modifier.fillMaxSize())
        // a soft dark fade behind her words and the button, so they read over any background
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, LunaColors.Background.copy(alpha = 0.92f)))),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // her words while she answers (or yours while she's hearing them)
            if (last != null && (chat.busy || !last.mine) && !last.waiting) {
                Text(
                    last.text,
                    color = if (last.mine) LunaColors.Muted else LunaColors.Text,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(LunaColors.Background.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            note?.let { Text(it, color = LunaColors.Pink, fontSize = 13.sp) }
            if (chat.status.isNotEmpty()) Text(chat.status, color = LunaColors.Muted, fontSize = 13.sp)
            chat.ask?.let { AskCard(it, onAnswer) }
            if (chat.busy) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Pink),
                    modifier = Modifier.size(width = 120.dp, height = 56.dp)) { Text("Stop") }
            } else {
                MicButton(chat, onClick = onMic, size = 76)
            }
        }
    }
}

@Composable
private fun Bubble(m: Message) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        // a small corner on the side the bubble came from, like a speech tail
        val shape = if (m.mine) RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(if (m.mine) LunaColors.Mine else LunaColors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                m.text,
                color = if (m.waiting) LunaColors.Muted else LunaColors.Text,
                fontStyle = if (m.waiting) FontStyle.Italic else FontStyle.Normal,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            if (m.cut) Text("stopped", color = LunaColors.Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Line(text: String, color: Color, dot: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 13.sp)
    }
}

/** Her permission question, asked on this device and answered here. Two minutes unanswered is a no. */
@Composable
private fun AskCard(ask: imbored.luna.station.station.StationClient.Ask, answer: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(LunaColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Luna asks: ${ask.title}", color = if (ask.warn) LunaColors.Pink else LunaColors.Text, fontWeight = FontWeight.Bold)
        if (ask.body.isNotBlank()) {
            Text(
                ask.body, color = LunaColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { answer(true) }) { Text("Allow") }
            Button(onClick = { answer(false) }, colors = ButtonDefaults.buttonColors(containerColor = LunaColors.Surface)) {
                Text("Deny", color = LunaColors.Text)
            }
        }
    }
}

/** Where she is, whether she's reachable, her certificate, and Forget. */
@Composable
private fun InfoDialog(s: Screen.Paired, onClose: () -> Unit, onCheck: () -> Unit, onForget: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (confirm) "Forget this PC?" else "Luna at ${s.host}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) {
                    Text(
                        "Pairing again needs a new code from the PC. This only clears this device: " +
                            "to remove it from Luna too, use Unpair on the settings page, Relay stations."
                    )
                } else {
                    Text(
                        when (val link = s.link) {
                            Link.Checking -> "Checking..."
                            Link.Connected -> "Connected."
                            Link.Removed -> "This device was unpaired on the PC."
                            is Link.Down -> link.why
                        }
                    )
                    Text("Luna's certificate (SHA-256), to compare with the PC:", fontSize = 13.sp)
                    Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            if (confirm) TextButton(onClick = onForget) { Text("Forget") }
            else TextButton(onClick = onCheck, enabled = s.link != Link.Checking) { Text("Check again") }
        },
        dismissButton = {
            Row {
                if (!confirm) TextButton(onClick = { confirm = true }) { Text("Forget this PC") }
                TextButton(onClick = { if (confirm) confirm = false else onClose() }) { Text(if (confirm) "Keep" else "Close") }
            }
        },
    )
}
