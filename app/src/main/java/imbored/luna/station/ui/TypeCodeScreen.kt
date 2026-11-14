package imbored.luna.station.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import imbored.luna.station.StationViewModel.Screen

/** Pairing without the camera: the PC's address and the one-time code, or the whole link. */
@Composable
fun TypeCodeScreen(s: Screen.Typing, onPair: (String, String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var address by rememberSaveable { mutableStateOf(s.address) }
    var code by rememberSaveable { mutableStateOf(s.code) }

    Page {
        Title("Luna")
        Note("The address is the PC's, like 192.168.1.20. The code is in the link under the QR code, after code=. Or paste the whole link into the address box.")
        if (s.note != null) Note(s.note, LunaColors.Pink)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("PC address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Pairing code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        )
        Button(onClick = { onPair(address, code) }, enabled = address.isNotBlank()) { Text("Pair") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
