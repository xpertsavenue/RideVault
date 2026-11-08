package io.github.xpertsavenue.ridevault.driver.bikevee

import java.time.LocalDate
import java.time.LocalDateTime

/** Geparste Gerät→App-Rahmen. Alle Werte sind bereits metrisch (siehe [Units]). */
sealed interface Response {
    val command: Int

    data class Battery(val level: Int, val percent: Int) : Response {
        override val command get() = Cmd.BATTERY
    }

    data class Version(val version: String, val buildDate: LocalDate?) : Response {
        override val command get() = Cmd.VERSION
    }

    /** Kopf einer Fahrt (Antwort auf 0x16). [rideCount] == 0: nichts zu übertragen. */
    data class RideHeader(
        val startTime: LocalDateTime?,
        val gpsCount: Long,
        val sampleCount: Long,
        val rideCount: Int,
    ) : Response {
        override val command get() = Cmd.RIDE_REQUEST
        val totalRecords: Long get() = gpsCount + sampleCount
    }

    data class Gps(val time: LocalDateTime?, val latitude: Double, val longitude: Double) : Response {
        override val command get() = Cmd.GPS_RECORD
    }

    data class SampleRecord(
        val temperatureC: Double,
        val speedKmh: Double,
        val gradientPercent: Double,
        val altitudeM: Int,
        val distanceKm: Double,
        val heartRate: Int,
        val cadenceRpm: Int,
        val powerW: Int,
    ) : Response {
        override val command get() = Cmd.SAMPLE_RECORD
    }

    /** Zusammenfassungswerte; [command] bestimmt die Bedeutung (siehe [Cmd]). */
    data class Metric(override val command: Int, val a: Double, val b: Double? = null) : Response

    data object RideEnd : Response {
        override val command get() = Cmd.RIDE_END
    }

    data class NewRides(val available: Boolean) : Response {
        override val command get() = Cmd.NEW_RIDES
    }

    /** Antwort auf einen Einstellungsbefehl (gleiche Nummer). */
    data class SettingAck(override val command: Int) : Response

    data class Unknown(override val command: Int, val frame: Frame) : Response
}

/** Umrechnung. Die Imperial-Faktoren sind eine Annahme (Original rechnet unklar), siehe Doku. */
internal object Units {
    const val MILE_KM = 1.609344
    const val FOOT_M = 0.3048

    fun distance(value: Double, imperial: Boolean) = if (imperial) value * MILE_KM else value
    fun height(value: Double, imperial: Boolean) = if (imperial) value * FOOT_M else value
    fun temperature(value: Double, imperial: Boolean) = if (imperial) (value - 32.0) * 5.0 / 9.0 else value
}

object BikeveeParser {
    /**
     * Wandelt einen Rahmen in eine [Response]. Unbekannte Befehle ergeben [Response.Unknown].
     * Feldlayout: docs/bikevee-protokoll.md.
     */
    fun parse(f: Frame): Response = when (f.command) {
        Cmd.BATTERY -> f.u8(0).let { Response.Battery(it, (it * 20).coerceAtMost(100)) }

        Cmd.VERSION -> Response.Version(
            version = (0..3).joinToString(".") { Bcd.decode(f.u8(it)).toString() },
            buildDate = runCatching {
                LocalDate.of(2000 + Bcd.decode(f.u8(4)), Bcd.decode(f.u8(5)), Bcd.decode(f.u8(6)))
            }.getOrNull(),
        )

        Cmd.RIDE_REQUEST -> Response.RideHeader(
            startTime = Bcd.dateTime(f, 1),
            gpsCount = f.u32le(7),
            sampleCount = f.u32le(11),
            rideCount = f.u8(15),
        )

        Cmd.GPS_RECORD -> Response.Gps(
            time = Bcd.dateTime(f, 0),
            latitude = f.f32le(7).toDouble(),
            longitude = f.f32le(12).toDouble(),
        )

        Cmd.SAMPLE_RECORD -> Response.SampleRecord(
            temperatureC = f.i16le(0) / 10.0,
            speedKmh = f.u16le(2) / 10.0,
            gradientPercent = f.i16le(4) / 10.0,
            altitudeM = f.i16le(6),
            distanceKm = f.u16le(8) / 10.0,
            heartRate = f.u8(10),
            cadenceRpm = f.u8(11),
            powerW = f.u16le(12),
        )

        Cmd.SUMMARY_DURATION ->
            Response.Metric(f.command, (f.u8(0) * 3600 + f.u8(1) * 60 + f.u8(2)).toDouble())

        Cmd.SUMMARY_DISTANCE, Cmd.SUMMARY_AVG_SPEED, Cmd.SUMMARY_MAX_SPEED, Cmd.TOTAL_DISTANCE ->
            Response.Metric(f.command, Units.distance(f.u32le(0) / 10.0, imperial = f.u8(4) != 0))

        Cmd.SUMMARY_TEMPERATURE ->
            Response.Metric(f.command, Units.temperature(f.i16le(0).toDouble(), imperial = f.u8(2) != 0))

        Cmd.SUMMARY_CLIMB ->
            Response.Metric(f.command, Units.height(f.u32le(0).toDouble(), imperial = f.u8(4) != 0))

        Cmd.SUMMARY_ALTITUDE ->
            Response.Metric(f.command, Units.height(f.i16le(0).toDouble(), imperial = f.u8(4) != 0))

        Cmd.SUMMARY_GRADIENT -> Response.Metric(f.command, f.u8(0).toDouble(), f.u8(1).toDouble())

        // Gesamtzeit: Rohwert / 10, Einheit der Zeit unverifiziert.
        Cmd.TOTAL_TIME -> Response.Metric(f.command, f.u32le(0) / 10.0)

        // Original liest Anstieg und Abstieg aus denselben Bytes (vermutlich ein Bug); nur Anstieg wird übernommen.
        Cmd.TOTAL_CLIMB ->
            Response.Metric(f.command, Units.height(f.u32le(0) / 10.0, imperial = f.u8(8) != 0))

        Cmd.SUMMARY_HEART_RATE, Cmd.SUMMARY_CADENCE ->
            Response.Metric(f.command, f.u8(0).toDouble(), f.u8(1).toDouble())

        Cmd.SUMMARY_CALORIES -> Response.Metric(f.command, f.u16le(0).toDouble())

        Cmd.SUMMARY_POWER -> Response.Metric(f.command, f.u16le(0).toDouble(), f.u16le(2).toDouble())

        Cmd.RIDE_END -> Response.RideEnd

        Cmd.NEW_RIDES -> Response.NewRides(f.u8(0) == 1)

        Cmd.SET_TIME, Cmd.BACKLIGHT, Cmd.TIMED_BACKLIGHT, Cmd.AUTO_STANDBY,
        Cmd.TIME_ZONE, Cmd.LANGUAGE, Cmd.UNITS, Cmd.USER_PROFILE -> Response.SettingAck(f.command)

        else -> Response.Unknown(f.command, f)
    }
}
