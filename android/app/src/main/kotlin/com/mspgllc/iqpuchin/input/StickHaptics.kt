package com.mspgllc.iqpuchin.input

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * UI-CONTROL-02: a single, very short "tick" haptic (target feel: a
 * physical stick's "コッ") fired from exactly one call site --
 * [VirtualStickView.updateKnob]'s lock-acquisition branch, the same
 * place that already calls [InputActionListener.onMoveRequested] exactly
 * once per newly-locked direction. This object only plays the buzz; it
 * has no opinion on when that is, so it can never fire more than once
 * per lock or repeat while a lock is merely held.
 */
object StickHaptics {
    private const val TICK_DURATION_MS = 15L

    /** Out of 255 -- a crisp, short pulse rather than a soft buzz. */
    private const val TICK_AMPLITUDE = 180

    fun tick(context: Context) {
        val vibrator = vibratorFrom(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(TICK_DURATION_MS, TICK_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(TICK_DURATION_MS)
        }
    }

    private fun vibratorFrom(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
