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
import android.app.Activity
import android.net.Uri
import android.view.WindowManager
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    val attach: (Uri) -> Unit,
    val detach: () -> Unit,
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
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var keepAwake by remember { mutableStateOf(prefs.keepAwake) }
    var startPortrait by remember { mutableStateOf(prefs.startPortrait) }
    var portrait by rememberSaveable { mutableStateOf(prefs.startPortrait) }   // Chat | Portrait: both share the one turn
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Keep the screen on while she's open, if asked (Settings): a station on a stand shouldn't go dark.
    DisposableEffect(keepAwake) {
        val window = (context as? Activity)?.window
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var micNote by rememberSaveable { mutableStateOf<String?>(null) }
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
    // The attach button's picker: Android's own document picker, no storage permission needed.
    // It offers only the kinds of file this Luna takes (pictures only if she can see them).
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(act.attach) }
    val pickTypes = chat.attachments?.let { a ->
        val docs = a.docTypes + (if (a.docTypes.any { it.startsWith("text/") }) listOf("text/*") else emptyList())
        ((if (a.canSee != false) a.imageTypes else emptyList()) + docs).distinct().toTypedArray()
    }
    val list = rememberLazyListState()
    // Newest at the bottom; the list is reversed, so item 0 is the newest. Follow it only
    // when you're already there, so reading back isn't yanked away. Your own message always jumps.
    val last = chat.messages.lastOrNull()
    LaunchedEffect(chat.messages.size) {
        if (last?.mine == true || list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }

    if (showSettings) {
        SettingsScreen(
            s,
            keepAwake = keepAwake, onKeepAwake = { keepAwake = it; prefs.keepAwake = it },
            startPortrait = startPortrait, onStartPortrait = { startPortrait = it; prefs.startPortrait = it },
            onCheck = act.check,
            onForget = { showInfo = true },
            onBack = { showSettings = false },
        )
    } else {
        // see-through while the portrait shows: her WebView is under this Compose tree (MainActivity),
        // and the activity's own background is the same colour
        val fill = if (portrait) Modifier else Modifier.background(LunaColors.Background)
        Column(Modifier.fillMaxSize().then(fill).safeDrawingPadding()) {
            Header(
                s, portrait, onSwitch = { portrait = it },
                onSettings = { showSettings = true },
                onForget = { showInfo = true },
            )
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
                    // while she answers, her newest bubble carries what she's doing and the stop button
                    items(chat.messages.asReversed(), key = { it.id }) {
                        Bubble(it, working = if (chat.busy && it === last && !it.mine) chat.status else null, onStop = act.stop)
                    }
                }
                (chat.note ?: micNote)?.let { Line(it, LunaColors.Pink) }
                if (chat.busy && last?.mine != false) {
                    // no reply bubble yet (sending, hearing you): the same line and stop button, under the list
                    Working(chat.status, act.stop, Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                } else if (!chat.busy && chat.status.isNotEmpty()) {
                    Line(chat.status, LunaColors.Muted, dot = LunaColors.Violet)
                }
                chat.ask?.let { AskCard(it, act.answer) }
                chat.picked?.let { PickedFile(it, act.detach) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val ready = text.isNotBlank() || chat.picked != null      // words, a file, or both
                    val sendNow = { if (!chat.busy && ready) { act.send(text); text = "" } }
                    // the paperclip, inside the box: a picture or a document, if this Luna takes them (GET /info)
                    val clip: (@Composable () -> Unit)? = if (pickTypes.isNullOrEmpty()) null else {
                        {
                            IconButton(onClick = { pick.launch(pickTypes) }, enabled = !chat.busy && !chat.recording) {
                                Icon(
                                    painterResource(R.drawable.ic_attach), contentDescription = "Attach a picture or file",
                                    tint = if (chat.picked != null) LunaColors.Violet else LunaColors.Muted,
                                )
                            }
                        }
                    }
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
                        trailingIcon = clip,
                    )
                    // (the paperclip moved into the text box: trailingIcon above)
                    // the mic, or a send arrow once there's something to send (the keyboard's Send works too)
                    if (ready && !chat.recording) {
                        FilledIconButton(
                            onClick = sendNow,
                            enabled = !chat.busy,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = LunaColors.Violet, contentColor = LunaColors.Background,
                                disabledContainerColor = LunaColors.Surface, disabledContentColor = LunaColors.Muted,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) { Icon(painterResource(R.drawable.ic_send), contentDescription = "Send", modifier = Modifier.size(22.dp)) }
                    } else {
                        MicButton(chat, onClick = mic, size = 48)
                    }
                }
                // Stop used to sit here; it's in her bubble now (Working)
            }
        }
    }

    if (showInfo) InfoDialog(s, forgetFirst = true, onClose = { showInfo = false }, onCheck = act.check, onForget = { showInfo = false; act.forget() })
}

@Composable
private fun Header(s: Screen.Paired, portrait: Boolean, onSwitch: (Boolean) -> Unit, onSettings: () -> Unit, onForget: () -> Unit) {
    // The address and status dot that sat on the right are gone: the line below the header
    // still says when she can't be reached, and the emblem's menu and Settings show the connection.
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // her emblem on the left opens the menu
        Box(Modifier.align(Alignment.CenterStart)) { EmblemMenu(s, onSettings = onSettings, onForget = onForget) }
        // the switch in the middle, in a track so the two halves read as one control
        Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(LunaColors.Surface).padding(3.dp)) {
            Switch2("Chat", !portrait) { onSwitch(false) }
            Switch2("Portrait", portrait) { onSwitch(true) }
        }
    }
    // The old header, before the switch was centred and the address dropped:
    // val (dot, words) = when (s.link) {
    //     Link.Checking -> LunaColors.Muted to "checking"
    //     Link.Connected -> LunaColors.Cyan to s.host
    //     Link.Removed -> LunaColors.Pink to "unpaired"
    //     is Link.Down -> LunaColors.Pink to "can't reach"
    // }
    // Row(
    //     Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    //     verticalAlignment = Alignment.CenterVertically,
    //     horizontalArrangement = Arrangement.spacedBy(12.dp),
    // ) {
    //     // her emblem opens the menu; the status on the right still opens the info box directly
    //     // Text("Luna", color = LunaColors.Pink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
    //     //     modifier = Modifier.clickable(onClick = onInfo))   // replaced by the emblem and its menu
    //     EmblemMenu(onSettings = onInfo, onForget = onForget)
    //     // the switch sits in a track, so the two halves read as one control
    //     Row(Modifier.clip(RoundedCornerShape(50)).background(LunaColors.Surface).padding(3.dp)) {
    //         Switch2("Chat", !portrait) { onSwitch(false) }
    //         Switch2("Portrait", portrait) { onSwitch(true) }
    //     }
    //     // the status gives way first on a narrow screen, never the switch
    //     Row(
    //         Modifier.weight(1f).clickable(onClick = onInfo),
    //         verticalAlignment = Alignment.CenterVertically,
    //         horizontalArrangement = Arrangement.End,
    //     ) {
    //         Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
    //         Text(
    //             " $words", color = LunaColors.Muted, fontSize = 13.sp, maxLines = 1,
    //             overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    //         )
    //     }
    // }
    if (s.link is Link.Removed || s.link is Link.Down) {
        Line((s.link as? Link.Down)?.why ?: "This device was unpaired on the PC. Tap the moon at the top left, then Forget this PC, and pair again.", LunaColors.Pink)
    }
}

/** The file waiting to go with the next message, with an x to drop it. */
@Composable
private fun PickedFile(f: imbored.luna.station.StationViewModel.Picked, onDrop: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(LunaColors.Surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(if (f.image) R.drawable.ic_image else R.drawable.ic_attach), contentDescription = null,
            tint = LunaColors.Violet, modifier = Modifier.size(18.dp))
        Text(
            "  ${f.name}", color = LunaColors.Text, fontSize = 13.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("  ${if (f.size >= 1 shl 20) "%.1f MB".format(f.size / 1048576.0) else "${(f.size + 1023) / 1024} KB"}",
            color = LunaColors.Muted, fontSize = 12.sp)
        IconButton(onClick = onDrop, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Don't send this file", tint = LunaColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** What she's doing ("Thinking", "Speaking"...) with a small stop button beside it. */
@Composable
private fun Working(status: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(LunaColors.Violet))
        Spacer(Modifier.width(6.dp))
        Text(status.ifEmpty { "Working" }, color = LunaColors.Muted, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        FilledIconButton(
            onClick = onStop,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = LunaColors.Pink, contentColor = LunaColors.Background),
            modifier = Modifier.size(30.dp),
        ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop", modifier = Modifier.size(16.dp)) }
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
            if (last != null && (chat.busy || !last.mine) && !last.waiting && last.text.isNotEmpty()) {
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
private fun Bubble(m: Message, working: String? = null, onStop: () -> Unit = {}) {
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
            m.file?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (m.text.isEmpty()) 0.dp else 4.dp)) {
                    Icon(painterResource(R.drawable.ic_attach), contentDescription = null, tint = LunaColors.Muted, modifier = Modifier.size(16.dp))
                    Text(" $name", color = LunaColors.Muted, fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (m.text.isNotEmpty()) Text(
                m.text,
                color = if (m.waiting) LunaColors.Muted else LunaColors.Text,
                fontStyle = if (m.waiting) FontStyle.Italic else FontStyle.Normal,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            if (m.cut) Text("stopped", color = LunaColors.Muted, fontSize = 11.sp)
            if (working != null) Working(working, onStop, Modifier.align(Alignment.End).padding(top = 6.dp))
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
private fun InfoDialog(s: Screen.Paired, forgetFirst: Boolean, onClose: () -> Unit, onCheck: () -> Unit, onForget: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(forgetFirst) }
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
                // opened on the question (the menu, Settings): Keep closes it; opened on the info, Keep goes back to it
                TextButton(onClick = { if (confirm && !forgetFirst) confirm = false else onClose() }) { Text(if (confirm) "Keep" else "Close") }
            }
        },
    )
}
