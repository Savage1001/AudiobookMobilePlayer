package app.localchapter.player

import android.content.Context

internal class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var wholeBookProgress: Boolean
        get() = prefs.getBoolean("wholeBookProgress", false)
        set(value) { prefs.edit().putBoolean("wholeBookProgress", value).apply() }

    var rewindSeconds: Int
        get() = prefs.getInt("rewind", 30)
        set(value) { prefs.edit().putInt("rewind", value).apply() }
    var forwardSeconds: Int
        get() = prefs.getInt("forward", 30)
        set(value) { prefs.edit().putInt("forward", value).apply() }
    var customTimerMinutes: Int
        get() = prefs.getInt("customTimer", 0)
        set(value) { prefs.edit().putInt("customTimer", value).apply() }
    var fadeTimer: Boolean
        get() = prefs.getBoolean("fadeTimer", true)
        set(value) { prefs.edit().putBoolean("fadeTimer", value).apply() }
    var shakeTimer: Boolean
        get() = prefs.getBoolean("shakeTimer", false)
        set(value) { prefs.edit().putBoolean("shakeTimer", value).apply() }
}
