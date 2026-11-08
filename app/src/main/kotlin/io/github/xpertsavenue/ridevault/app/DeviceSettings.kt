package io.github.xpertsavenue.ridevault.app

import android.content.Context
import io.github.xpertsavenue.ridevault.device.Backlight
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.SpeedUnit

/** Die Sprachen des Geräts in der Reihenfolge, in der es sie nummeriert (1 = Englisch ... 12 = Japanisch). */
val DeviceLanguages = listOf(
    "English", "Deutsch", "中文", "Čeština", "Polski", "Français", "Italiano", "Español", "Nederlands", "한국어", "Português", "日本語",
)

/** Zeitzonen des Geräts: Ost/West plus Zone 0..12 (wie in der Original-App), also ganze Stunden von UTC-12 bis UTC+12. */
const val MAX_TIME_ZONE = 12

/**
 * Ein Zeitzonen-Versatz des Geräts mit typischen Städten. Das Gerät kennt nur ganze Stunden und keine Sommerzeit;
 * die Städte sind Orientierung (Namen international, ohne Übersetzung).
 */
data class TimeZoneOption(val offset: Int, val cities: String) {
    val label: String get() = "UTC" + (if (offset >= 0) "+" else "−") + kotlin.math.abs(offset) + " · " + cities
    val hours: Int get() = kotlin.math.abs(offset)
    val east: Boolean get() = offset >= 0
}

val TimeZoneOptions = listOf(
    -12 to "Baker Island", -11 to "Pago Pago", -10 to "Honolulu", -9 to "Anchorage", -8 to "Los Angeles, Vancouver",
    -7 to "Denver, Phoenix", -6 to "Chicago, Mexico City", -5 to "New York, Toronto, Bogotá", -4 to "Halifax, Caracas",
    -3 to "São Paulo, Buenos Aires", -2 to "South Georgia", -1 to "Azores",
    0 to "London, Lisbon, Reykjavik", 1 to "Berlin, Paris, Rome, Madrid", 2 to "Athens, Helsinki, Kyiv, Cairo",
    3 to "Moscow, Istanbul, Riyadh", 4 to "Dubai, Baku", 5 to "Karachi, Tashkent", 6 to "Dhaka, Almaty",
    7 to "Bangkok, Jakarta", 8 to "Singapore, Beijing, Perth", 9 to "Tokyo, Seoul", 10 to "Brisbane, Vladivostok",
    11 to "Noumea, Solomon Islands", 12 to "Auckland, Fiji",
).map { TimeZoneOption(it.first, it.second) }

fun timeZoneOption(hours: Int, east: Boolean): TimeZoneOption? {
    val offset = if (east) hours else -hours
    return TimeZoneOptions.firstOrNull { it.offset == offset }
}

/**
 * Aktueller Versatz der Handy-Zeitzone in ganzen Stunden (inkl. Sommerzeit), oder null, wenn er keine ganze Stunde ist
 * (z. B. Indien, UTC+5:30) oder außerhalb von -12..+12 liegt. Das Gerät kann nur ganze Stunden.
 */
fun phoneTimeZoneOption(zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), now: java.time.Instant = java.time.Instant.now()): TimeZoneOption? {
    val seconds = zone.rules.getOffset(now).totalSeconds
    if (seconds % 3600 != 0) return null
    return TimeZoneOptions.firstOrNull { it.offset == seconds / 3600 }
}

/** Beleuchtungs-Dauern des Geräts in Sekunden (Original: 30 s, 1, 5, 10 Minuten). */
val BacklightSeconds = listOf(30, 60, 300, 600)

/**
 * Zuletzt an das Gerät gesendete Werte. Der BKV meldet seine Einstellungen nicht zurück (kein Lese-Befehl gefunden),
 * deshalb merkt sich die App, was sie zuletzt gesetzt hat.
 */
data class DeviceValues(
    val language: Int? = null,
    val speedUnit: SpeedUnit? = null,
    val tzHours: Int? = null,
    val tzEast: Boolean? = null,
    val backlight: Backlight? = null,
    val timedOn: Boolean? = null,
    val timedStart: Int? = null,
    val timedEnd: Int? = null,
) {
    /** Übernimmt eine erfolgreich gesendete Einstellung. */
    fun with(setting: DeviceSetting): DeviceValues = when (setting) {
        is DeviceSetting.Language -> copy(language = setting.index)
        is DeviceSetting.Units -> copy(speedUnit = setting.speed)
        is DeviceSetting.TimeZone -> copy(tzHours = setting.hours, tzEast = setting.east)
        is DeviceSetting.BacklightMode -> copy(backlight = setting.backlight)
        is DeviceSetting.TimedBacklight ->
            copy(timedOn = setting.startMinutes != null && setting.endMinutes != null, timedStart = setting.startMinutes, timedEnd = setting.endMinutes)
        else -> this
    }
}

class DeviceSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_values", Context.MODE_PRIVATE)

    fun load(address: String): DeviceValues {
        fun int(k: String) = if (prefs.contains("$address|$k")) prefs.getInt("$address|$k", 0) else null
        return DeviceValues(
            language = int("language"),
            speedUnit = prefs.getString("$address|unit", null)?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() },
            tzHours = int("tzHours"),
            tzEast = if (prefs.contains("$address|tzEast")) prefs.getBoolean("$address|tzEast", true) else null,
            backlight = prefs.getString("$address|backlight", null)?.let(::decodeBacklight),
            timedOn = if (prefs.contains("$address|timedOn")) prefs.getBoolean("$address|timedOn", false) else null,
            timedStart = int("timedStart"),
            timedEnd = int("timedEnd"),
        )
    }

    fun save(address: String, v: DeviceValues) {
        val e = prefs.edit()
        fun int(k: String, value: Int?) = if (value == null) e.remove("$address|$k") else e.putInt("$address|$k", value)
        int("language", v.language)
        if (v.speedUnit == null) e.remove("$address|unit") else e.putString("$address|unit", v.speedUnit.name)
        int("tzHours", v.tzHours)
        if (v.tzEast == null) e.remove("$address|tzEast") else e.putBoolean("$address|tzEast", v.tzEast)
        if (v.backlight == null) e.remove("$address|backlight") else e.putString("$address|backlight", encodeBacklight(v.backlight))
        if (v.timedOn == null) e.remove("$address|timedOn") else e.putBoolean("$address|timedOn", v.timedOn)
        int("timedStart", v.timedStart)
        int("timedEnd", v.timedEnd)
        e.apply()
    }

    private fun encodeBacklight(b: Backlight) = when (b) {
        Backlight.Off -> "off"
        Backlight.AlwaysOn -> "always"
        is Backlight.Timeout -> "t:${b.seconds}"
    }

    private fun decodeBacklight(s: String): Backlight? = when {
        s == "off" -> Backlight.Off
        s == "always" -> Backlight.AlwaysOn
        s.startsWith("t:") -> s.removePrefix("t:").toIntOrNull()?.let { Backlight.Timeout(it) }
        else -> null
    }
}
