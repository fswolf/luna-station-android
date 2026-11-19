package imbored.luna.station.ui

import android.content.Context

/**
 * The few things this device remembers about how you like it: plain settings, nothing secret
 * (the pairing lives in station/StationStore.kt, encrypted). Read once, written as you change them.
 */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Keep the screen on while the app is open: for a station on a stand. Off by default (battery). */
    var keepAwake: Boolean
        get() = p.getBoolean("keep_awake", false)
        set(v) = p.edit().putBoolean("keep_awake", v).apply()

    /** Play her voice on this device. Off: her replies are text only (Luna still speaks on the PC). */
    var voice: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()

    /** Open in the Portrait view instead of Chat. */
    var startPortrait: Boolean
        get() = p.getBoolean("start_portrait", false)
        set(v) = p.edit().putBoolean("start_portrait", v).apply()
}
