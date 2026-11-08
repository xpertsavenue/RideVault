package io.github.xpertsavenue.ridevault.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Zusätzliche Farben, die zur gewählten Akzentfarbe passen. Sie entstehen durch Verschieben des Farbtons der Akzentfarbe
 * (Nachbarfarben und Komplementärfarbe) und folgen deshalb jeder Änderung von Akzentfarbe oder Tag/Nacht automatisch.
 *
 * Reihenfolge: 0 Primär, 1 Sekundär, 2 Tertiär, 3..5 abgeleitete Farben.
 */
@Immutable
class AccentPalette(val colors: List<Color>) {
    val primary get() = colors[0]
    val secondary get() = colors[1]
    val tertiary get() = colors[2]
    val extra1 get() = colors[3]
    val extra2 get() = colors[4]
    val extra3 get() = colors[5]
}

val LocalAccents = staticCompositionLocalOf {
    AccentPalette(listOf(Color(0xFF2E7D32), Color(0xFF00796B), Color(0xFF0277BD), Color(0xFF6A1B9A), Color(0xFFF9A825), Color(0xFFC2185B)))
}

private fun hueShift(base: Color, degrees: Float, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(base.toArgb(), hsl)
    hsl[0] = (hsl[0] + degrees + 360f) % 360f
    // Kräftig genug, um als Akzent zu wirken, aber lesbar: im Dunkelmodus heller, im Hellmodus etwas dunkler
    hsl[1] = hsl[1].coerceIn(0.45f, 0.85f)
    hsl[2] = if (dark) hsl[2].coerceIn(0.62f, 0.78f) else hsl[2].coerceIn(0.32f, 0.5f)
    return Color(ColorUtils.HSLToColor(hsl))
}

fun accentPalette(scheme: ColorScheme, dark: Boolean): AccentPalette = AccentPalette(
    listOf(
        scheme.primary, scheme.secondary, scheme.tertiary,
        hueShift(scheme.primary, 40f, dark),
        hueShift(scheme.primary, -40f, dark),
        hueShift(scheme.primary, 180f, dark),
    ),
)

/** Feste, gut unterscheidbare Tag-Farben; ergänzen die zur Akzentfarbe passenden. */
val FixedTagColors = listOf(
    0xFFD32F2F, 0xFFF57C00, 0xFFFBC02D, 0xFF388E3C, 0xFF00897B, 0xFF1976D2, 0xFF7B1FA2, 0xFF5D4037,
).map { Color(it) }

/** Schwarz oder Weiß, je nachdem, was auf [background] besser lesbar ist. */
fun onColor(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White

private fun Color.luminance(): Float = ColorUtils.calculateLuminance(toArgb()).toFloat()
