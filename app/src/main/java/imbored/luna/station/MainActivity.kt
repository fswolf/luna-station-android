package imbored.luna.station

import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import imbored.luna.station.StationViewModel.Screen
import imbored.luna.station.ui.LunaColors
import imbored.luna.station.ui.LunaTheme
import imbored.luna.station.ui.ChatActions
import imbored.luna.station.ui.ChatScreen
import imbored.luna.station.ui.ScanScreen
import imbored.luna.station.ui.TypeCodeScreen
import imbored.luna.station.ui.UnpairedScreen
import imbored.luna.station.ui.WorkingScreen

/**
 * The one activity: shows whichever screen the view model says.
 *
 * Two layers in a plain FrameLayout: [portraitHost] underneath, the Compose tree on top. Her
 * portrait's WebView lives in the host, not in Compose, because hosted by Compose it loses its
 * GPU layers on some phones (ui/PortraitView.kt has the story); Compose goes see-through where
 * the portrait pane is.
 */
class MainActivity : ComponentActivity() {
    /** Where ui/PortraitView.kt puts her WebView: the layer under the Compose tree. */
    lateinit var portraitHost: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        portraitHost = FrameLayout(this)
        val compose = ComposeView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(LunaColors.Background.toArgb())
            addView(portraitHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(compose, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        compose.setContent {
            LunaTheme {
                val vm: StationViewModel = viewModel()
                val screen by vm.screen.collectAsStateWithLifecycle()
                when (val s = screen) {
                    is Screen.Unpaired -> UnpairedScreen(s.note, onScan = vm::scan, onType = vm::type, onCameraDenied = vm::cameraDenied)
                    Screen.Scanning -> ScanScreen(onFound = vm::scanned, onBack = vm::back, onError = vm::noCamera)
                    is Screen.Typing -> TypeCodeScreen(s, onPair = vm::typed, onBack = vm::back)
                    is Screen.Working -> WorkingScreen(s.step)
                    is Screen.Paired -> {
                        val chat by vm.chat.collectAsStateWithLifecycle()
                        ChatScreen(s, chat, ChatActions(
                            send = vm::send, talk = vm::talk, stop = vm::stop, answer = vm::answer,
                            check = vm::check, forget = vm::forget, leave = vm::leave, pairing = vm::portraitPairing,
                        ))
                    }
                }
            }
        }
    }
}
