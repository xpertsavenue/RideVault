package io.github.xpertsavenue.ridevault.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/** Kontrastverhältnis zweier Farben nach WCAG (1 = gleich, 21 = Schwarz auf Weiß). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * Sorgt für lesbaren Text: Hat [foreground] auf [background] weniger als [minRatio] Kontrast (WCAG: 4,5 für normalen Text),
 * wird er schrittweise zu Schwarz bzw. Weiß gezogen, je nachdem, was vom Hintergrund absticht. Der Farbton bleibt dabei erhalten.
 */
fun ensureContrast(foreground: Color, background: Color, minRatio: Float = 4.5f): Color {
    if (contrastRatio(foreground, background) >= minRatio) return foreground
    val target = if (background.luminance() > 0.5f) Color.Black else Color.White
    for (step in 1..20) {
        val candidate = lerp(foreground, target, step / 20f)
        if (contrastRatio(candidate, background) >= minRatio) return candidate
    }
    return target
}

/** Wie [ensureContrast] für ARGB-Werte (z. B. beim Zeichnen von Bildern). */
fun ensureContrast(foreground: Int, background: Int, minRatio: Double = 4.5): Int {
    val bg = ColorUtils.setAlphaComponent(background, 0xFF)
    if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(foreground, 0xFF), bg) >= minRatio) return foreground
    val target = if (ColorUtils.calculateLuminance(bg) > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    for (step in 1..20) {
        val candidate = ColorUtils.blendARGB(foreground, target, step / 20f)
        if (ColorUtils.calculateContrast(ColorUtils.setAlphaComponent(candidate, 0xFF), bg) >= minRatio) return candidate
    }
    return target
}
