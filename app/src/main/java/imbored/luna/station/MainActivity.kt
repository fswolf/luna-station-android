package imbored.luna.station

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import imbored.luna.station.StationViewModel.Screen
import imbored.luna.station.ui.LunaTheme
import imbored.luna.station.ui.ChatActions
import imbored.luna.station.ui.ChatScreen
import imbored.luna.station.ui.ScanScreen
import imbored.luna.station.ui.TypeCodeScreen
import imbored.luna.station.ui.UnpairedScreen
import imbored.luna.station.ui.WorkingScreen

/** The one activity: shows whichever screen the view model says. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
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
