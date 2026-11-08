package io.github.xpertsavenue.ridevault.fit

import io.github.xpertsavenue.ridevault.model.GpsPoint
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideSummary
import io.github.xpertsavenue.ridevault.model.Sample
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Eine Aufzeichnung aus einer FIT-Datei (nur das, was RideVault braucht). Alle Werte in SI-Einheiten bzw. Rohgrößen. */
data class FitRecord(
    /** Sekunden seit 1989-12-31 00:00 UTC (FIT-Epoche). */
    val timestamp: Long?,
    val latitudeSemicircles: Int?,
    val longitudeSemicircles: Int?,
    val altitudeM: Double?,
    val heartRate: Int?,
    val cadence: Int?,
    val distanceM: Double?,
    val speedMs: Double?,
    val power: Int?,
    val temperatureC: Int?,
)

data class FitSession(
    val startTime: Long?,
    val totalElapsedSeconds: Double?,
    val totalTimerSeconds: Double?,
    val totalDistanceM: Double?,
    val totalCalories: Int?,
    val avgSpeedMs: Double?,
    val maxSpeedMs: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgCadence: Int?,
    val maxCadence: Int?,
    val avgPower: Int?,
    val maxPower: Int?,
    val totalAscentM: Int?,
    val avgTemperatureC: Int?,
)

data class FitActivity(val records: List<FitRecord>, val sessions: List<FitSession>) {
    val firstTimestamp: Long? get() = sessions.firstOrNull()?.startTime ?: records.firstNotNullOfOrNull { it.timestamp }
}

class FitFormatException(message: String) : RuntimeException(message)

/**
 * Minimaler FIT-Parser (Garmin Flexible and Interoperable Data Transfer): liest Header, Definitions- und Datennachrichten
 * einschließlich komprimierter Zeitstempel-Köpfe und Entwicklerfelder (diese werden übersprungen) und wertet die Nachrichten
 * `record` (20) und `session` (18) aus. Tolerant gegenüber abgeschnittenen Dateien: es werden alle vollständigen Nachrichten gelesen.
 */
object FitParser {
    const val FIT_EPOCH_OFFSET_SECONDS = 631_065_600L

    private const val MSG_SESSION = 18
    private const val MSG_RECORD = 20

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)
    private class Definition(val bigEndian: Boolean, val globalNum: Int, val fields: List<FieldDef>, val devBytes: Int) {
        val dataSize: Int get() = fields.sumOf { it.size } + devBytes
    }

    /** Größe je Basistyp (Nummer in den unteren 5 Bit des Basistyp-Bytes). */
    private val typeSize = intArrayOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)
    private val signed = setOf(1, 3, 5, 14)

    fun parse(bytes: ByteArray): FitActivity {
        if (bytes.size < 12) throw FitFormatException("Datei zu kurz (${bytes.size} Byte)")
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != 12 && headerSize != 14) throw FitFormatException("Ungültige Kopfgröße $headerSize")
        if (String(bytes, 8, 4, Charsets.ISO_8859_1) != ".FIT") throw FitFormatException("Kein FIT-Dateikopf")
        val dataSize = readInt(bytes, 4, 4, signed = false, bigEndian = false).toInt()
        // Nutzdaten enden vor den 2 CRC-Bytes; bei abgeschnittenen Dateien nehmen wir, was da ist
        val end = minOf(headerSize + dataSize, bytes.size)

        val defs = HashMap<Int, Definition>()
        val records = ArrayList<FitRecord>()
        val sessions = ArrayList<FitSession>()
        var pos = headerSize
        var lastTimestamp = 0L

        while (pos < end) {
            val h = bytes[pos++].toInt() and 0xFF
            val localType: Int
            var compressedOffset = -1
            if (h and 0x80 != 0) { // komprimierter Zeitstempel-Kopf: nur Datennachricht
                localType = (h shr 5) and 0x03
                compressedOffset = h and 0x1F
            } else if (h and 0x40 != 0) { // Definitionsnachricht
                val hasDev = h and 0x20 != 0
                if (pos + 5 > end) break
                pos++ // reserviert
                val big = bytes[pos++].toInt() == 1
                val global = readInt(bytes, pos, 2, signed = false, bigEndian = big).toInt()
                pos += 2
                val n = bytes[pos++].toInt() and 0xFF
                if (pos + n * 3 > end) break
                val fields = ArrayList<FieldDef>(n)
                repeat(n) {
                    fields += FieldDef(bytes[pos].toInt() and 0xFF, bytes[pos + 1].toInt() and 0xFF, bytes[pos + 2].toInt() and 0xFF)
                    pos += 3
                }
                var devBytes = 0
                if (hasDev) {
                    if (pos >= end) break
                    val nDev = bytes[pos++].toInt() and 0xFF
                    if (pos + nDev * 3 > end) break
                    repeat(nDev) { devBytes += bytes[pos + 1].toInt() and 0xFF; pos += 3 }
                }
                defs[h and 0x0F] = Definition(big, global, fields, devBytes)
                continue
            } else {
                localType = h and 0x0F
            }

            val def = defs[localType] ?: throw FitFormatException("Datennachricht ohne Definition (lokaler Typ $localType)")
            if (pos + def.dataSize > end) break // abgeschnitten

            // Felder lesen
            val values = HashMap<Int, Long>()
            var p = pos
            for (f in def.fields) {
                val type = f.baseType and 0x1F
                val size = typeSize.getOrElse(type) { 1 }
                // nur einzelne Zahlenwerte; Arrays und Strings werden übersprungen
                if (type != 7 && type != 13 && f.size == size) {
                    val raw = readInt(bytes, p, size, signed = type in signed, bigEndian = def.bigEndian)
                    if (!isInvalid(raw, type)) values[f.num] = raw
                }
                p += f.size
            }
            pos += def.dataSize

            var ts = values[253]
            if (compressedOffset >= 0) {
                // Zeitstempel aus den unteren 5 Bit rekonstruieren
                ts = if (compressedOffset >= (lastTimestamp and 0x1F)) (lastTimestamp and 0x1F.inv().toLong()) + compressedOffset
                else (lastTimestamp and 0x1F.inv().toLong()) + 0x20 + compressedOffset
            }
            if (ts != null) lastTimestamp = ts

            when (def.globalNum) {
                MSG_RECORD -> records += FitRecord(
                    timestamp = ts,
                    latitudeSemicircles = values[0]?.toInt(),
                    longitudeSemicircles = values[1]?.toInt(),
                    altitudeM = (values[78] ?: values[2])?.let { it / 5.0 - 500.0 },
                    heartRate = values[3]?.toInt(),
                    cadence = values[4]?.toInt(),
                    distanceM = values[5]?.let { it / 100.0 },
                    speedMs = (values[73] ?: values[6])?.let { it / 1000.0 },
                    power = values[7]?.toInt(),
                    temperatureC = values[13]?.toInt(),
                )
                MSG_SESSION -> sessions += FitSession(
                    startTime = values[2],
                    totalElapsedSeconds = values[7]?.let { it / 1000.0 },
                    totalTimerSeconds = values[8]?.let { it / 1000.0 },
                    totalDistanceM = values[9]?.let { it / 100.0 },
                    totalCalories = values[11]?.toInt(),
                    avgSpeedMs = (values[124] ?: values[14])?.let { it / 1000.0 },
                    maxSpeedMs = (values[125] ?: values[15])?.let { it / 1000.0 },
                    avgHeartRate = values[16]?.toInt(),
                    maxHeartRate = values[17]?.toInt(),
                    avgCadence = values[18]?.toInt(),
                    maxCadence = values[19]?.toInt(),
                    avgPower = values[20]?.toInt(),
                    maxPower = values[21]?.toInt(),
                    totalAscentM = values[22]?.toInt(),
                    avgTemperatureC = values[57]?.toInt(),
                )
            }
        }
        return FitActivity(records, sessions)
    }

    private fun readInt(b: ByteArray, off: Int, size: Int, signed: Boolean, bigEndian: Boolean): Long {
        var v = 0L
        for (i in 0 until size) {
            val byte = b[off + (if (bigEndian) i else size - 1 - i)].toLong() and 0xFF
            v = (v shl 8) or byte
        }
        if (signed && size < 8) {
            val shift = 64 - size * 8
            v = (v shl shift) shr shift
        }
        return v
    }

    /** Ungültig-Markierung je Basistyp (z. B. uint8 = 0xFF, sint16 = 0x7FFF). */
    private fun isInvalid(raw: Long, type: Int): Boolean = when (type) {
        0, 2, 13 -> raw == 0xFFL
        1 -> raw == 0x7FL
        3 -> raw == 0x7FFFL
        4 -> raw == 0xFFFFL
        5 -> raw == 0x7FFFFFFFL
        6 -> raw == 0xFFFFFFFFL
        10 -> raw == 0L
        11 -> raw == 0L
        12 -> raw == 0L
        else -> false
    }
}

/** Umwandlung einer FIT-Aktivität in eine RideVault-[Ride]. */
object FitRides {
    private const val SEMICIRCLE_TO_DEGREES = 180.0 / 2147483648.0

    fun fitToLocal(fitSeconds: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochSecond(fitSeconds + FitParser.FIT_EPOCH_OFFSET_SECONDS), zone)

    /**
     * @param startOverride Startzeit der Fahrt (z. B. aus der Fahrtenliste des Geräts); sonst Start aus Sitzung/erstem Datensatz.
     * @param zone Zone, in der die (UTC-)FIT-Zeiten als lokale Zeit dargestellt werden.
     */
    fun toRide(deviceKey: String, activity: FitActivity, zone: ZoneId, startOverride: LocalDateTime? = null): Ride {
        val track = ArrayList<GpsPoint>()
        val samples = ArrayList<Sample>()
        for (r in activity.records) {
            val ts = r.timestamp
            val lat = r.latitudeSemicircles
            val lon = r.longitudeSemicircles
            if (ts != null && lat != null && lon != null) {
                track += GpsPoint(fitToLocal(ts, zone), lat * SEMICIRCLE_TO_DEGREES, lon * SEMICIRCLE_TO_DEGREES)
            }
            samples += Sample(
                temperatureC = r.temperatureC?.toDouble(),
                speedKmh = r.speedMs?.let { it * 3.6 },
                altitudeM = r.altitudeM?.let { Math.round(it).toInt() },
                distanceKm = r.distanceM?.let { it / 1000.0 },
                heartRate = r.heartRate,
                cadenceRpm = r.cadence,
                powerW = r.power,
            )
        }
        val s = activity.sessions.firstOrNull()
        val first = activity.firstTimestamp
        val start = startOverride ?: first?.let { fitToLocal(it, zone) } ?: throw FitFormatException("Fahrt ohne Zeitangabe")
        val lastDistance = samples.lastOrNull { it.distanceKm != null }?.distanceKm
        val summary = RideSummary(
            durationSeconds = (s?.totalTimerSeconds ?: s?.totalElapsedSeconds)?.toInt(),
            distanceKm = s?.totalDistanceM?.let { it / 1000.0 } ?: lastDistance,
            avgSpeedKmh = s?.avgSpeedMs?.let { it * 3.6 },
            maxSpeedKmh = s?.maxSpeedMs?.let { it * 3.6 },
            temperatureC = s?.avgTemperatureC?.toDouble(),
            climbM = s?.totalAscentM?.toDouble(),
            avgHeartRate = s?.avgHeartRate,
            maxHeartRate = s?.maxHeartRate,
            avgCadenceRpm = s?.avgCadence,
            maxCadenceRpm = s?.maxCadence,
            calories = s?.totalCalories,
            avgPowerW = s?.avgPower,
            maxPowerW = s?.maxPower,
        )
        return Ride(deviceKey, start, summary, track, samples)
    }
}
