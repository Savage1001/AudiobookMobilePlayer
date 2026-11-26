package app.localchapter.player

import android.content.Context

internal class PlaybackPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    var wholeBookProgress: Boolean
        get() = prefs.getBoolean("wholeBookProgress", false)
        set(value) { prefs.edit().putBoolean("wholeBookProgress", value).apply() }

    var skipSeconds: Long
        get() = prefs.getLong("skipSeconds", prefs.getInt("rewind", 30).toLong())
        set(value) { prefs.edit().putLong("skipSeconds", value).remove("rewind").remove("forward").apply() }
    var fadeTimer: Boolean
        get() = prefs.getBoolean("fadeTimer", true)
        set(value) { prefs.edit().putBoolean("fadeTimer", value).apply() }
    var shakeTimer: Boolean
        get() = prefs.getBoolean("shakeTimer", false)
        set(value) { prefs.edit().putBoolean("shakeTimer", value).apply() }
}
