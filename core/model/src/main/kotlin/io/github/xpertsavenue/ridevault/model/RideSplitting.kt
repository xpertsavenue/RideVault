package io.github.xpertsavenue.ridevault.model

import java.time.Duration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * @param gapMinutes  Pause zwischen zwei GPS-Punkten, ab der eine neue Fahrt beginnt.
 * @param minDistanceKm  Teilstücke, die kürzer sind (z. B. GPS-Jitter im Stand), werden verworfen.
 */
data class SplitOptions(val gapMinutes: Long = 30, val minDistanceKm: Double = 0.3)

fun haversineKm(a: GpsPoint, b: GpsPoint): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
    return 2 * 6371.0 * atan2(sqrt(h), sqrt(1 - h))
}

private fun pathKm(points: List<GpsPoint>) = points.zipWithNext().sumOf { (a, b) -> haversineKm(a, b) }

/**
 * Teilt eine vom Gerät gelieferte „Fahrt“ in Einzelfahrten.
 *
 * Beobachtung am echten Gerät: Der BKV beendet eine Aufzeichnung nicht, wenn man nur anhält. Über Wochen sammelt er alles
 * in einem Block (hier: 8.9. bis 12.10., 1800 GPS-Punkte und 1800 Messpunkte, zusammen 11,4 km). Wir trennen deshalb an
 * Zeitsprüngen in der GPS-Spur.
 *
 * - Nur ein verwertbares Teilstück: Die Fahrt bleibt mit der Zusammenfassung des Geräts erhalten (Spur ohne Fremdpunkte).
 * - Mehrere Teilstücke: Je Teilstück entsteht eine Fahrt mit selbst berechneter Zusammenfassung. Die Messpunkte
 *   (ohne Zeitstempel) werden über die kumulierte Strecke zugeordnet, die in beiden Datenreihen übereinstimmt.
 */
fun Ride.splitIntoRides(options: SplitOptions = SplitOptions()): List<Ride> {
    // Unmittelbar wiederholte Zeitstempel sind Füllsätze
    val pts = ArrayList<GpsPoint>(track.size)
    for (p in track) if (pts.isEmpty() || pts.last().time != p.time) pts += p
    if (pts.size < 2) return listOf(copy(track = pts))

    val segments = ArrayList<List<GpsPoint>>()
    var cur = ArrayList<GpsPoint>().apply { add(pts[0]) }
    val maxGap = Duration.ofMinutes(options.gapMinutes)
    for (i in 1 until pts.size) {
        val gap = Duration.between(pts[i - 1].time, pts[i].time)
        if (gap.isNegative || gap > maxGap) {
            segments += cur
            cur = ArrayList()
        }
        cur += pts[i]
    }
    segments += cur

    val lengths = segments.map(::pathKm)
    val keep = segments.indices.filter { lengths[it] >= options.minDistanceKm }
    if (keep.isEmpty()) return listOf(copy(track = pts))
    if (keep.size == 1) return listOf(copy(track = segments[keep[0]]))

    // Messpunkte über die kumulierte Strecke den Teilstücken zuordnen
    val total = lengths.sum()
    val sampleTotal = samples.maxOfOrNull { it.distanceKm ?: 0.0 } ?: 0.0
    val ratio = if (total > 0) sampleTotal / total else 0.0
    val scale = if (ratio in 0.5..2.0) ratio else null
    val starts = lengths.runningFold(0.0) { acc, l -> acc + l }

    return keep.map { k ->
        val seg = segments[k]
        val segSamples = if (scale == null) emptyList() else {
            val from = starts[k] * scale
            val to = starts[k + 1] * scale
            samples.filter { s ->
                val d = s.distanceKm ?: 0.0
                d >= from - 1e-9 && (d < to - 1e-9 || (k == segments.lastIndex && d <= to + 1e-9))
            }
        }
        val base = segSamples.firstOrNull()?.distanceKm ?: 0.0
        val normalized = segSamples.map { it.copy(distanceKm = it.distanceKm?.minus(base)) }
        Ride(
            deviceKey = deviceKey,
            startTime = seg.first().time,
            summary = summarize(seg, lengths[k] * (scale ?: 1.0), normalized),
            track = seg,
            samples = normalized,
        )
    }
}

private const val MOVING_KMH = 2.0
private const val MAX_STEP_SECONDS = 30L
private const val CLIMB_HYSTERESIS_M = 3

private fun summarize(track: List<GpsPoint>, distanceKm: Double, samples: List<Sample>): RideSummary {
    // Fahrzeit: nur Intervalle mit Bewegung (wie ein Radcomputer), keine Standzeiten
    var moving = 0L
    for ((a, b) in track.zipWithNext()) {
        val dt = Duration.between(a.time, b.time).seconds
        if (dt in 1..MAX_STEP_SECONDS && haversineKm(a, b) / dt * 3600.0 >= MOVING_KMH) moving += dt
    }
    fun nonZero(v: List<Int?>) = v.filterNotNull().filter { it > 0 }
    val hr = nonZero(samples.map { it.heartRate })
    val cad = nonZero(samples.map { it.cadenceRpm })
    val pow = nonZero(samples.map { it.powerW })
    val alt = samples.mapNotNull { it.altitudeM }
    // Anstieg nur dort zählen, wo die Strecke wirklich wächst: im Stand rauscht das Höhensignal und würde den Anstieg verdoppeln
    val altWhileMoving = ArrayList<Int>()
    var lastKm = -1.0
    for (s in samples) {
        val d = s.distanceKm ?: continue
        val a = s.altitudeM ?: continue
        if (d > lastKm + 1e-9) {
            altWhileMoving += a
            lastKm = d
        }
    }
    val temps = samples.mapNotNull { it.temperatureC }
    return RideSummary(
        durationSeconds = moving.toInt(),
        distanceKm = distanceKm,
        avgSpeedKmh = if (moving > 0) distanceKm / (moving / 3600.0) else null,
        maxSpeedKmh = samples.mapNotNull { it.speedKmh }.maxOrNull(),
        temperatureC = temps.takeIf { it.isNotEmpty() }?.average(),
        climbM = altWhileMoving.takeIf { it.isNotEmpty() }?.let(::climb),
        altitudeM = alt.maxOrNull()?.toDouble(),
        avgHeartRate = hr.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        maxHeartRate = hr.maxOrNull(),
        avgCadenceRpm = cad.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        maxCadenceRpm = cad.maxOrNull(),
        avgPowerW = pow.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        maxPowerW = pow.maxOrNull(),
    )
}

/** Summe der Anstiege mit Hysterese, damit Rauschen im Höhensignal nicht als Anstieg zählt. */
private fun climb(altitudes: List<Int>): Double {
    var ref = altitudes.first()
    var up = 0.0
    for (a in altitudes) {
        if (a > ref + CLIMB_HYSTERESIS_M) {
            up += a - ref
            ref = a
        } else {
            ref = min(ref, a)
        }
    }
    return max(up, 0.0)
}
