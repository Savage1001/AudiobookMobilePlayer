package app.localchapter.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player

internal class SleepTimer(
    context: Context,
    private val handler: Handler,
    private val player: Player,
    private val settings: PlaybackPreferences,
    private val onChanged: () -> Unit
) : SensorEventListener {
    private companion object {
        const val FINAL_WINDOW_MS = 30_000L
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val tick = Runnable { refresh() }
    private var sensing = false
    private var firstJolt = 0L
    private var jolted = false
    private var previousVolume: Float? = null
    private var durationMs = 0L
    private var extendUntil = 0L
    private var resumeOnExtend = false
    var deadline = 0L
        private set

    val canExtend: Boolean get() = SystemClock.elapsedRealtime() < extendUntil

    fun set(minutes: Int) {
        durationMs = minutes * 60_000L
        restart()
    }

    private fun restart() {
        restoreVolume()
        extendUntil = 0L
        resumeOnExtend = false
        deadline = if (durationMs == 0L) 0L else SystemClock.elapsedRealtime() + durationMs
        onChanged()
        refresh()
    }

    fun extend() {
        if (deadline == 0L && !canExtend) return
        val resume = deadline == 0L && resumeOnExtend
        restart()
        if (resume) player.play()
    }

    fun refresh() {
        handler.removeCallbacks(tick)
        val now = SystemClock.elapsedRealtime()
        val remaining = deadline - now
        if (deadline != 0L && remaining <= 0) {
            resumeOnExtend = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            extendUntil = now + FINAL_WINDOW_MS
            deadline = 0L
            player.pause()
            restoreVolume()
            onChanged()
        } else if (deadline != 0L && remaining <= FINAL_WINDOW_MS && settings.fadeTimer) {
            val volume = previousVolume ?: player.volume.also { previousVolume = it }
            player.volume = volume * (remaining.toFloat() / FINAL_WINDOW_MS)
        } else {
            restoreVolume()
        }
        val graceRemaining = extendUntil - now
        val inFinalWindow = deadline != 0L && remaining <= FINAL_WINDOW_MS
        setSensing(settings.shakeTimer && (inFinalWindow || graceRemaining > 0))
        if (deadline != 0L) {
            val delay = if (inFinalWindow) minOf(remaining, 250L) else remaining - FINAL_WINDOW_MS
            handler.postDelayed(tick, delay)
        } else if (graceRemaining > 0) {
            handler.postDelayed(tick, graceRemaining)
        } else if (extendUntil != 0L) {
            extendUntil = 0L
            resumeOnExtend = false
            onChanged()
        }
    }

    fun clearExtension() {
        if (deadline == 0L && extendUntil != 0L) set(0)
    }

    fun close() {
        handler.removeCallbacks(tick)
        setSensing(false)
        restoreVolume()
    }

    private fun restoreVolume() {
        previousVolume?.let { player.volume = it }
        previousVolume = null
    }

    private fun setSensing(enabled: Boolean) {
        if (enabled == sensing) return
        sensing = if (enabled && accelerometer != null) {
            sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler) == true
        } else {
            sensors?.unregisterListener(this)
            false
        }
        firstJolt = 0L
        jolted = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val high = x * x + y * y + z * z > 24f * 24f
        if (high && !jolted) {
            val now = SystemClock.elapsedRealtime()
            if (now - firstJolt in 60L..750L) extend()
            else firstJolt = now
        }
        jolted = high
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
