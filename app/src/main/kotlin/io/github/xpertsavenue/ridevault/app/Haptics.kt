package io.github.xpertsavenue.ridevault.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Haptisches Feedback, das sich in den Einstellungen abschalten lässt ([AppSettings.haptics]).
 *
 * Ist in Android "Touch-Feedback" aus (Einstellungen > Ton und Vibration), unterdrückt das System die Vibrationen der Oberfläche.
 * Der App-Schalter ist dann die ausdrückliche Entscheidung des Nutzers: Die App vibriert in diesem Fall selbst.
 */
@Stable
class Haptics(private val context: Context, private val feedback: HapticFeedback, private val enabled: Boolean) {
    /** Langes Drücken, z. B. Auswahl beginnt. */
    fun longPress() = perform(HapticFeedbackType.LongPress, VibrationEffect.EFFECT_HEAVY_CLICK)

    /** Auswahl an/aus. */
    fun toggle(on: Boolean) =
        perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff, if (on) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)

    /** Eine Wischgeste hat die Schwelle erreicht: Loslassen löst die Aktion aus. */
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate, VibrationEffect.EFFECT_TICK)

    /** Aktion ausgeführt (archiviert, gelöscht, geändert). */
    fun confirm() = perform(HapticFeedbackType.Confirm, VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun perform(type: HapticFeedbackType, effect: Int) {
        if (!enabled) return
        if (systemTouchFeedbackOn()) feedback.performHapticFeedback(type) else vibrate(effect)
    }

    private fun systemTouchFeedbackOn(): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1 }.getOrDefault(true)

    @Suppress("DEPRECATION")
    private fun vibrate(effect: Int) {
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            }
            if (!vibrator.hasVibrator()) return
            // Nutzungsart "Medien": wird vom Touch-Feedback-Schalter des Systems nicht erfasst
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            vibrator.vibrate(VibrationEffect.createPredefined(effect), attrs)
        }
    }
}

@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val feedback = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(feedback, context, enabled) { Haptics(context, feedback, enabled) }
}
