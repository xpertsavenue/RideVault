package io.github.xpertsavenue.ridevault.app

import android.content.Context
import io.github.xpertsavenue.ridevault.model.GroupBy
import io.github.xpertsavenue.ridevault.model.SortKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MapStyle { STANDARD, CYCLING, TERRAIN, SATELLITE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** true: Akzentfarbe des Systems (Material You, z. B. Pixel-Hintergrund). */
    val useSystemColor: Boolean = true,
    /** Eigene Akzentfarbe (ARGB), wenn [useSystemColor] false ist. */
    val seedColor: Int = 0xFF2E7D32.toInt(),
    val mapStyle: MapStyle = MapStyle.STANDARD,
    /** Strecke in der Karte nach Geschwindigkeit grün→rot einfärben. */
    val speedColors: Boolean = true,
    /** "system" oder ein Sprach-Tag wie "de". */
    val language: String = "system",
    /** Fahrtenliste: Darstellung, Sortierung und Gruppierung. */
    val viewMode: ViewMode = ViewMode.FULL,
    val sortKey: SortKey = SortKey.DATE,
    val sortDescending: Boolean = true,
    val groupBy: GroupBy = GroupBy.NONE,
    /** Uhrzeit des Geräts beim Verbinden automatisch stellen. */
    val autoSyncTime: Boolean = true,
    /** Haptisches Feedback (Vibration) bei Auswahl, Wischen und Aktionen. */
    val haptics: Boolean = true,
)

enum class ViewMode { COMPACT, FULL }

/** Speichert [AppSettings] lokal in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(new: AppSettings) {
        _state.value = new
        prefs.edit()
            .putString("themeMode", new.themeMode.name)
            .putBoolean("useSystemColor", new.useSystemColor)
            .putInt("seedColor", new.seedColor)
            .putString("mapStyle", new.mapStyle.name)
            .putBoolean("speedColors", new.speedColors)
            .putString("language", new.language)
            .putString("viewMode", new.viewMode.name)
            .putString("sortKey", new.sortKey.name)
            .putBoolean("sortDescending", new.sortDescending)
            .putString("groupBy", new.groupBy.name)
            .putBoolean("autoSyncTime", new.autoSyncTime)
            .putBoolean("haptics", new.haptics)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = prefs.getString("themeMode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            useSystemColor = prefs.getBoolean("useSystemColor", d.useSystemColor),
            seedColor = prefs.getInt("seedColor", d.seedColor),
            mapStyle = prefs.getString("mapStyle", null)?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: d.mapStyle,
            speedColors = prefs.getBoolean("speedColors", d.speedColors),
            language = prefs.getString("language", null) ?: d.language,
            viewMode = enumOf("viewMode", d.viewMode),
            sortKey = enumOf("sortKey", d.sortKey),
            sortDescending = prefs.getBoolean("sortDescending", d.sortDescending),
            groupBy = enumOf("groupBy", d.groupBy),
            autoSyncTime = prefs.getBoolean("autoSyncTime", d.autoSyncTime),
            haptics = prefs.getBoolean("haptics", d.haptics),
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
