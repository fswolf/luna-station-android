package imbored.luna.station.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** What a fresh install shows until it's paired with a PC. */
@Composable
fun UnpairedScreen(note: String?, onScan: () -> Unit, onType: () -> Unit, onCameraDenied: () -> Unit) {
    val context = LocalContext.current
    val hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onScan() else onCameraDenied()
    }

    Page {
        Title("Luna")
        Text("Not paired yet", color = LunaColors.Text, fontSize = 20.sp)
        Note("On the PC: settings page, Relay stations, Add a phone or tablet. Then scan the code it shows.")
        if (note != null) Note(note, LunaColors.Pink)
        Spacer(Modifier.height(8.dp))
        if (hasCamera) {
            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) onScan() else askCamera.launch(Manifest.permission.CAMERA)
            }) { Text("Scan the code") }
        }
        TextButton(onClick = onType) { Text(if (hasCamera) "Type it instead" else "Type the address and code") }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun UnpairedPreview() {
    LunaTheme { UnpairedScreen(null, {}, {}, {}) }
}
