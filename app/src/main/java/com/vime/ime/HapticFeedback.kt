@file:Suppress("DEPRECATION")
package com.vime.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.vime.ime.engine.KeyboardTuning
import com.vime.ime.data.KeyboardPrefs

object HapticFeedback {
    fun play(context: Context, level: Int, longPress: Boolean = false) {
        val advanced = KeyboardPrefs.advanced(context) && level > 0
        val duration = if (advanced) KeyboardPrefs.duration(context, longPress).toLong() else KeyboardTuning.duration(level, longPress)
        if (duration == 0L || Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration,
                if (vibrator.hasAmplitudeControl()) { if (advanced) KeyboardPrefs.amplitude(context,longPress) else KeyboardTuning.amplitude(level) } else VibrationEffect.DEFAULT_AMPLITUDE))
            else vibrator.vibrate(duration)
        }
    }
}
