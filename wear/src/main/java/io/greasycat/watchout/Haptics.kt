package io.greasycat.watchout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The three come-back signals, each with a deliberately distinct feel so you can tell
 * them apart without looking:
 *   - THINKING  → one short soft tick ("started")
 *   - DONE      → one long pulse ("finished")
 *   - NEEDS_INPUT → a fast triple burst ("you're needed", most urgent)
 * Order = priority (ascending): when several fire at once, buzz the highest ordinal.
 */
enum class Buzz(val timings: LongArray) {
    THINKING(longArrayOf(0, 60)),
    DONE(longArrayOf(0, 320)),
    NEEDS_INPUT(longArrayOf(0, 180, 100, 180, 100, 180));

    companion object {
        /** Status string → signal, or null for statuses that shouldn't buzz. */
        fun of(status: String): Buzz? = when (status) {
            "thinking", "update" -> THINKING
            "done" -> DONE
            "needs_input" -> NEEDS_INPUT
            else -> null
        }
    }
}

fun buzz(context: Context, kind: Buzz) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(VibrationEffect.createWaveform(kind.timings, -1))
}
