package io.github.xpertsavenue.ridevault.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.StoredRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Schreibt Fahrten auf Wunsch in Health Connect (lokaler Gesundheitsspeicher von Android) und entfernt sie wieder.
 * Es gibt keinen automatischen Export. Jede Fahrt bekommt feste Kennungen ([key] plus Endung je Datenart), damit ein erneuter
 * Export dieselben Einträge ersetzt und das Entfernen genau diese Einträge trifft. Welche Fahrten exportiert sind, merkt sich die
 * App nur lokal.
 */
class HealthExporter(private val context: Context) {
    enum class Availability { OK, MISSING, UPDATE }

    private val prefs = context.getSharedPreferences("health_export", 0)
    private val _exported = MutableStateFlow(runCatching { prefs.getStringSet("keys", emptySet()).orEmpty().toSet() }.getOrDefault(emptySet()))

    /** Kennungen der Fahrten, die diese App in Health Connect geschrieben hat. */
    val exported: StateFlow<Set<String>> = _exported.asStateFlow()

    /** Stabile Kennung einer Fahrt (Gerät + Startzeit), unabhängig von der Datenbank-ID. */
    fun key(ride: Ride) = "ridevault-${ride.deviceKey}-${ride.startTime}"

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.OK
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE
        else -> Availability.MISSING
    }

    private val client get() = HealthConnectClient.getOrCreate(context)

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(PowerRecord::class),
        HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
        "android.permission.health.WRITE_EXERCISE_ROUTE",
    )

    /** Berechtigungen, die noch fehlen. */
    suspend fun missingPermissions(): Set<String> = permissions - client.permissionController.getGrantedPermissions()

    private fun remember(keys: Set<String>, add: Boolean) {
        val next = if (add) _exported.value + keys else _exported.value - keys
        _exported.value = next
        runCatching { prefs.edit().putStringSet("keys", next).apply() }
    }

    suspend fun export(rides: List<StoredRide>, zone: ZoneId) = withContext(Dispatchers.Default) {
        val version = System.currentTimeMillis()
        val c = client
        rides.forEach { s ->
            val recs = records(s, zone, version)
            // Erst die alten Einträge dieser Fahrt löschen, dann neu schreiben: so bleibt nichts Veraltetes (z. B. eine Datenart, die jetzt fehlt)
            val id = key(s.ride)
            types.forEach { (suffix, type) -> c.deleteRecords(type, emptyList(), listOf(id + suffix)) }
            c.insertRecords(recs)
            remember(setOf(key(s.ride)), add = true)
        }
    }

    suspend fun remove(rides: List<StoredRide>) = withContext(Dispatchers.Default) {
        val c = client
        rides.forEach { s ->
            val id = key(s.ride)
            types.forEach { (suffix, type) -> c.deleteRecords(type, emptyList(), listOf(id + suffix)) }
            remember(setOf(id), add = false)
        }
    }

    private val types: List<Pair<String, KClass<out Record>>> = listOf(
        "" to ExerciseSessionRecord::class, "-dist" to DistanceRecord::class, "-hr" to HeartRateRecord::class,
        "-speed" to SpeedRecord::class, "-power" to PowerRecord::class, "-cad" to CyclingPedalingCadenceRecord::class,
    )

    /** Höchstens [max] gleichmäßig verteilte Elemente (Health Connect begrenzt die Größe einer Anfrage). */
    private fun <T> List<T>.thin(max: Int): List<T> =
        if (size <= max) this else List(max) { this[(it.toLong() * (size - 1) / (max - 1)).toInt()] }

    private fun records(s: StoredRide, zone: ZoneId, version: Long): List<Record> {
        val r = s.ride
        val start = r.startTime.atZone(zone).toInstant()
        val lastTrack = r.track.lastOrNull()?.time?.atZone(zone)?.toInstant()
        val movingSec = r.summary.durationSeconds?.toLong()?.takeIf { it > 0 }
        // Die Fahrzeit des Geräts zählt nur die Bewegung; die Spur läuft über die ganze Zeit inklusive Pausen. Passt die Spur nicht
        // in die Fahrzeit, ist die Einheit so lang wie die Spur (Start bis letzter Punkt) und die Lücken werden als Pausen markiert.
        val trackSpan = lastTrack?.let { it.epochSecond - start.epochSecond }?.takeIf { it > 0 }
        val durationSec = maxOf(movingSec ?: 0L, trackSpan ?: 0L).takeIf { it > 0 } ?: error("Fahrt ohne Dauer")
        // Health Connect verlangt Spurpunkte vor dem Ende der Einheit (Ende ausgeschlossen): eine Sekunde Luft nach dem letzten Punkt
        val end = start.plusSeconds(durationSec + 1)
        val allTimes = r.track.map { it.time.atZone(zone).toInstant() }
        val realTimes = allTimes.isNotEmpty() && allTimes.all { !it.isBefore(start) && !it.isAfter(end) } &&
            allTimes.zipWithNext().all { (a, b) -> !b.isBefore(a) }
        // Lücken von mehr als 30 s zwischen zwei Spurpunkten sind Pausen (Gerät zeichnet im Stand nicht auf)
        val pauses = if (!realTimes) emptyList() else allTimes.zipWithNext().filter { (a, b) -> b.epochSecond - a.epochSecond > 30 }
        val startOff = zone.rules.getOffset(start)
        val endOff = zone.rules.getOffset(end)
        val id = key(r)
        fun meta(suffix: String) = Metadata.manualEntry(clientRecordId = id + suffix, clientRecordVersion = version)

        // Spur: echte Zeiten, wenn sie in die Fahrt passen; sonst gleichmäßig über die Fahrt verteilt
        val route = if (r.track.isEmpty()) null else {
            val pts = r.track.thin(3000)
            ExerciseRoute(
                pts.mapIndexed { i, p ->
                    val t = if (realTimes) p.time.atZone(zone).toInstant() else spread(start, durationSec, i, pts.size)
                    ExerciseRoute.Location(time = t, latitude = p.latitude, longitude = p.longitude)
                },
            )
        }

        val out = ArrayList<Record>()
        out += ExerciseSessionRecord(
            startTime = start, startZoneOffset = startOff, endTime = end, endZoneOffset = endOff,
            metadata = meta(""),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            title = s.name?.takeIf { it.isNotBlank() },
            exerciseRoute = route,
            segments = pauses.map { (a, b) -> ExerciseSegment(a, b, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) },
        )
        r.summary.distanceKm?.takeIf { it > 0 }?.let {
            out += DistanceRecord(start, startOff, end, endOff, Length.kilometers(it), meta("-dist"))
        }

        // Zeitabschnitte ohne Pausen: Die Messwerte des Geräts haben keine Zeitstempel und laufen nur während der Bewegung.
        // Deshalb werden sie anteilig über die Bewegungszeit verteilt (Pausen übersprungen), nicht über die ganze Dauer.
        val active = ArrayList<Pair<Instant, Instant>>().also { list ->
            var cur = start
            pauses.forEach { (a, b) -> if (a.isAfter(cur)) list += cur to a; cur = b }
            if (end.isAfter(cur)) list += cur to end
        }
        val activeMs = active.sumOf { (a, b) -> b.toEpochMilli() - a.toEpochMilli() }
        fun activeAt(i: Int, n: Int): Instant {
            var rem = ((i + 0.5) / n * activeMs).toLong()
            for ((a, b) in active) {
                val len = b.toEpochMilli() - a.toEpochMilli()
                if (rem < len) return a.plusMillis(rem)
                rem -= len
            }
            return start
        }
        val samples = r.samples.thin(600)
        val n = samples.size
        val hr = samples.mapIndexedNotNull { i, x -> x.heartRate?.takeIf { it in 1..300 }?.let { HeartRateRecord.Sample(activeAt(i, n), it.toLong()) } }
        if (hr.isNotEmpty()) out += HeartRateRecord(start, startOff, end, endOff, hr, meta("-hr"))

        // Geschwindigkeit aus der Spur (Strecke / Zeit zwischen Punkten): passt zur Strecke, die Verbraucher wie Fit daraus aufsummieren.
        // In Pausen steht 0. Ohne brauchbare Zeiten bleibt der Wert des Geräts, verteilt wie die übrigen Messwerte.
        val speed = if (realTimes && r.track.size >= 2) gpsSpeed(r.track, allTimes) else
            samples.mapIndexedNotNull { i, x -> x.speedKmh?.takeIf { it >= 0 }?.let { SpeedRecord.Sample(activeAt(i, n), Velocity.kilometersPerHour(it)) } }
        if (speed.isNotEmpty()) out += SpeedRecord(start, startOff, end, endOff, speed, meta("-speed"))
        val power = samples.mapIndexedNotNull { i, x -> x.powerW?.takeIf { it > 0 }?.let { PowerRecord.Sample(activeAt(i, n), Power.watts(it.toDouble())) } }
        if (power.isNotEmpty()) out += PowerRecord(start, startOff, end, endOff, power, meta("-power"))
        val cad = samples.mapIndexedNotNull { i, x -> x.cadenceRpm?.takeIf { it > 0 }?.let { CyclingPedalingCadenceRecord.Sample(activeAt(i, n), it.toDouble()) } }
        if (cad.isNotEmpty()) out += CyclingPedalingCadenceRecord(start, startOff, end, endOff, cad, meta("-cad"))
        return out
    }

    /** Geschwindigkeit je Spurpunkt aus Strecke und Zeit zum Vorgänger; Lücken über 30 s gelten als Stillstand (0 am Rand der Lücke). */
    private fun gpsSpeed(track: List<io.github.xpertsavenue.ridevault.model.GpsPoint>, times: List<Instant>): List<SpeedRecord.Sample> {
        val raw = ArrayList<SpeedRecord.Sample>()
        var last: Instant? = null
        for (i in 1 until track.size) {
            val dt = times[i].toEpochMilli() - times[i - 1].toEpochMilli()
            if (dt <= 0) continue
            if (dt > 30_000) {
                // Stillstand: kurz nach dem letzten Punkt und kurz vor dem nächsten 0
                listOf(times[i - 1].plusMillis(500), times[i].minusMillis(500)).forEach { t ->
                    if (last == null || t.isAfter(last)) { raw += SpeedRecord.Sample(t, Velocity.kilometersPerHour(0.0)); last = t }
                }
                continue
            }
            val kmh = io.github.xpertsavenue.ridevault.model.haversineKm(track[i - 1], track[i]) / (dt / 3_600_000.0)
            if (last == null || times[i].isAfter(last)) { raw += SpeedRecord.Sample(times[i], Velocity.kilometersPerHour(kmh.coerceIn(0.0, 120.0))); last = times[i] }
        }
        return raw.thin(2000)
    }

    /** Zeitpunkt des i-ten von [n] gleichmäßig über die Fahrt verteilten Punkten (immer innerhalb von Start und Ende). */
    private fun spread(start: Instant, durationSec: Long, i: Int, n: Int): Instant =
        start.plusMillis(((i + 0.5) / n * durationSec * 1000).toLong().coerceIn(0, durationSec * 1000 - 1))
}
