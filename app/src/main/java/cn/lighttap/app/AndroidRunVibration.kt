package cn.lighttap.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.SystemClock

/** A finite notification vibration with no sound, dialog or input action. */
internal class AndroidRunVibration(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)

    private var priorityUntil = 0L
    fun remind(): Boolean = if (SystemClock.elapsedRealtime() < priorityUntil) false
        else play(longArrayOf(0, 180, 140, 180))

    fun remindBeforeBreak(): Boolean {
        val pattern = longArrayOf(0, 450, 220, 450, 220, 450)
        priorityUntil = SystemClock.elapsedRealtime() + pattern.sum()
        return play(pattern)
    }

    private fun play(pattern: LongArray): Boolean {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return false
        return try {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) device.vibrate(effect,
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build())
            else {
                @Suppress("DEPRECATION")
                device.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel() { vibrator?.cancel() }
}
