package io.github.xpertsavenue.ridevault.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Wählt die App-Sprache: Standard ist die Systemsprache, optional eine eigene (Einstellung "language"). */
object LocaleHelper {
    /** Die Systemsprache beim App-Start (vor jeder Überschreibung). */
    private var systemLocale: Locale = Locale.getDefault()

    val supported: List<Pair<String, String>> = listOf(
        "en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español", "it" to "Italiano",
        "nl" to "Nederlands", "pl" to "Polski", "cs" to "Čeština", "pt" to "Português",
    )

    fun init() {
        systemLocale = Locale.getDefault()
    }

    fun wrap(base: Context): Context {
        val tag = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "system") ?: "system"
        if (tag == "system") {
            Locale.setDefault(systemLocale)
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
