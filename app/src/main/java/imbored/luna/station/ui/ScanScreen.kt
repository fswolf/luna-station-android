package imbored.luna.station.ui

import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The camera, full screen, until it sees a QR code. */
@Composable
fun ScanScreen(onFound: (String) -> Unit, onBack: () -> Unit, onError: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(LunaColors.Background)) {
        QrCamera(onFound, onError, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Point it at the QR code on Luna's settings page",
                color = LunaColors.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(LunaColors.Background.copy(alpha = 0.7f)).padding(8.dp),
            )
            Button(onClick = onBack) { Text("Cancel") }
        }
    }
}

/** CameraX preview plus a frame analyser. Releases the camera when it leaves the screen. */
@Composable
private fun QrCamera(onFound: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val found by rememberUpdatedState(onFound)
    val failed by rememberUpdatedState(onError)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(modifier = modifier, factory = { ctx ->
        PreviewView(ctx).also { view ->
            providerFuture.addListener({
                if (disposed.get()) return@addListener   // cancelled before the camera was ready
                try {
                    val provider = providerFuture.get()
                    // Fire tablets and phones have a back camera; a few devices only a front one.
                    val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                        .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
                        ?: return@addListener failed("No camera found. Type the address and code instead.")
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, QrAnalyzer { text -> view.post { found(text) } }) }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, selector, preview, analysis)
                } catch (e: Exception) {
                    failed("The camera wouldn't start (${e.message ?: e.javaClass.simpleName}). Type the address and code instead.")
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
    })
}
