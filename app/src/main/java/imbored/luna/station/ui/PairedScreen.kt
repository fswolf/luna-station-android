package imbored.luna.station.ui

// Superseded by ChatScreen.kt (2026-11-13): the paired screen became the conversation, and
// its status, certificate and Forget moved into the info box behind the header.
// Kept commented out for reference; safe to delete.

// import androidx.compose.material3.AlertDialog
// import androidx.compose.material3.Button
// import androidx.compose.material3.Text
// import androidx.compose.material3.TextButton
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.getValue
// import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.saveable.rememberSaveable
// import androidx.compose.runtime.setValue
// import androidx.compose.ui.text.font.FontFamily
// import androidx.compose.ui.unit.sp
// import androidx.lifecycle.compose.LifecycleResumeEffect
// import imbored.luna.station.StationViewModel.Link
// import imbored.luna.station.StationViewModel.Screen
//
// /** Paired: where she is and whether she's reachable. Talking to her comes next. */
// @Composable
// fun PairedScreen(s: Screen.Paired, onCheck: () -> Unit, onForget: () -> Unit) {
//     // Every time the app comes back to the front, not just when it starts: the PC may
//     // have gone to sleep, or unpaired this device, while it was in the background.
//     LifecycleResumeEffect(Unit) {
//         onCheck()
//         onPauseOrDispose { }
//     }
//     var confirmForget by rememberSaveable { mutableStateOf(false) }
//
//     Page {
//         Title("Luna")
//         when (val link = s.link) {
//             Link.Checking -> Note("Checking ${s.host}...", LunaColors.Text)
//             Link.Connected -> Note("Paired with Luna at ${s.host}", LunaColors.Cyan)
//             Link.Removed -> Note("This device was unpaired on the PC. Forget it here and pair again.", LunaColors.Pink)
//             is Link.Down -> Note(link.why, LunaColors.Pink)
//         }
//         Button(onClick = onCheck, enabled = s.link != Link.Checking) { Text("Check again") }
//         Note("Luna's certificate (SHA-256), to compare with the PC if you like:")
//         Text(s.fingerprint, color = LunaColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
//         TextButton(onClick = { confirmForget = true }) { Text("Forget this PC") }
//     }
//
//     if (confirmForget) {
//         AlertDialog(
//             onDismissRequest = { confirmForget = false },
//             title = { Text("Forget this PC?") },
//             text = {
//                 Text(
//                     "Pairing again needs a new code from the PC. This only clears this device: " +
//                         "to remove it from Luna too, use Unpair on the settings page, Relay stations."
//                 )
//             },
//             confirmButton = {
//                 TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") }
//             },
//             dismissButton = {
//                 TextButton(onClick = { confirmForget = false }) { Text("Keep") }
//             },
//         )
//     }
// }
