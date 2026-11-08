package io.github.xpertsavenue.ridevault.export

import io.github.xpertsavenue.ridevault.model.GpsPoint
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.Sample
import io.github.xpertsavenue.ridevault.model.haversineKm
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exportformate für Fahrten. Die Zeiten des Geräts sind lokale Zeiten ohne Zone; GPX und TCX brauchen UTC.
 * Deshalb wird die Zone ([zone], üblicherweise die des Handys) explizit angegeben.
 *
 * Messpunkte (Höhe, Puls, Trittfrequenz, Leistung) haben auf dem Gerät keine Zeitstempel und werden den GPS-Punkten
 * deshalb anteilig nach Position in der Aufzeichnung zugeordnet. Das ist eine Näherung.
 */
object Exporters {
    private val utc: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    private fun LocalDateTime.toUtc(zone: ZoneId): String =
        utc.format(atZone(zone).withZoneSameInstant(ZoneOffset.UTC))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Messpunkt, der zum i-ten von [n] GPS-Punkten gehört (anteilig), oder null. */
    private fun sampleFor(i: Int, n: Int, samples: List<Sample>): Sample? {
        if (samples.isEmpty() || n == 0) return null
        return samples[(i.toLong() * samples.size / n).toInt().coerceIn(0, samples.lastIndex)]
    }

    private fun f(v: Double, digits: Int = 6) = String.format(Locale.ROOT, "%.${digits}f", v)

    /** GPX 1.1 mit Garmin-Erweiterung für Puls und Trittfrequenz (nur wenn vorhanden). */
    fun gpx(ride: Ride, name: String?, zone: ZoneId): String {
        val title = name?.takeIf { it.isNotBlank() } ?: ride.startTime.toString().replace('T', ' ')
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RideVault\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("  <metadata><name>").append(esc(title)).append("</name><time>").append(ride.startTime.toUtc(zone)).append("</time></metadata>\n")
        sb.append("  <trk>\n    <name>").append(esc(title)).append("</name>\n    <trkseg>\n")
        ride.track.forEachIndexed { i, p ->
            val s = sampleFor(i, ride.track.size, ride.samples)
            sb.append("      <trkpt lat=\"").append(f(p.latitude)).append("\" lon=\"").append(f(p.longitude)).append("\">")
            s?.altitudeM?.let { sb.append("<ele>").append(it).append("</ele>") }
            sb.append("<time>").append(p.time.toUtc(zone)).append("</time>")
            val hr = s?.heartRate?.takeIf { it > 0 }
            val cad = s?.cadenceRpm?.takeIf { it > 0 }
            if (hr != null || cad != null) {
                sb.append("<extensions><gpxtpx:TrackPointExtension>")
                hr?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
                cad?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
                sb.append("</gpxtpx:TrackPointExtension></extensions>")
            }
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Eine GPX-Datei mit einer Spur (`trk`) je Fahrt; Fahrten ohne GPS-Punkte werden ausgelassen. */
    fun gpxCombined(rides: List<Pair<Ride, String?>>, zone: ZoneId): String {
        val withTrack = rides.filter { it.first.track.isNotEmpty() }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RideVault\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("  <metadata><name>RideVault</name></metadata>\n")
        for ((ride, name) in withTrack) {
            val title = name?.takeIf { it.isNotBlank() } ?: ride.startTime.toString().replace('T', ' ')
            sb.append("  <trk>\n    <name>").append(esc(title)).append("</name>\n    <trkseg>\n")
            ride.track.forEachIndexed { i, p ->
                val s = sampleFor(i, ride.track.size, ride.samples)
                sb.append("      <trkpt lat=\"").append(f(p.latitude)).append("\" lon=\"").append(f(p.longitude)).append("\">")
                s?.altitudeM?.let { sb.append("<ele>").append(it).append("</ele>") }
                sb.append("<time>").append(p.time.toUtc(zone)).append("</time>")
                val hr = s?.heartRate?.takeIf { it > 0 }
                val cad = s?.cadenceRpm?.takeIf { it > 0 }
                if (hr != null || cad != null) {
                    sb.append("<extensions><gpxtpx:TrackPointExtension>")
                    hr?.let { sb.append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
                    cad?.let { sb.append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
                    sb.append("</gpxtpx:TrackPointExtension></extensions>")
                }
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** TCX (Training Center XML) mit einer Runde für die ganze Fahrt. */
    fun tcx(ride: Ride, zone: ZoneId): String {
        val track = ride.track
        val start = (track.firstOrNull()?.time ?: ride.startTime).toUtc(zone)
        val elapsed = if (track.size >= 2) java.time.Duration.between(track.first().time, track.last().time).seconds else 0L
        val totalSeconds = ride.summary.durationSeconds?.toLong() ?: elapsed
        val distM = (ride.summary.distanceKm ?: cumulativeKm(track).lastOrNull() ?: 0.0) * 1000.0
        val cum = cumulativeKm(track)
        val scale = if (cum.isNotEmpty() && cum.last() > 0 && ride.summary.distanceKm != null) ride.summary.distanceKm!! / cum.last() else 1.0

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
        sb.append("xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
        sb.append("  <Activities>\n    <Activity Sport=\"Biking\">\n      <Id>").append(start).append("</Id>\n")
        sb.append("      <Lap StartTime=\"").append(start).append("\">\n")
        sb.append("        <TotalTimeSeconds>").append(totalSeconds).append("</TotalTimeSeconds>\n")
        sb.append("        <DistanceMeters>").append(f(distM, 1)).append("</DistanceMeters>\n")
        ride.summary.maxSpeedKmh?.let { sb.append("        <MaximumSpeed>").append(f(it / 3.6, 2)).append("</MaximumSpeed>\n") }
        sb.append("        <Calories>").append(ride.summary.calories ?: 0).append("</Calories>\n")
        sb.append("        <Intensity>Active</Intensity>\n        <TriggerMethod>Manual</TriggerMethod>\n        <Track>\n")
        track.forEachIndexed { i, p ->
            val s = sampleFor(i, track.size, ride.samples)
            sb.append("          <Trackpoint><Time>").append(p.time.toUtc(zone)).append("</Time>")
            sb.append("<Position><LatitudeDegrees>").append(f(p.latitude)).append("</LatitudeDegrees><LongitudeDegrees>")
            sb.append(f(p.longitude)).append("</LongitudeDegrees></Position>")
            s?.altitudeM?.let { sb.append("<AltitudeMeters>").append(it).append("</AltitudeMeters>") }
            sb.append("<DistanceMeters>").append(f(cum[i] * scale * 1000.0, 1)).append("</DistanceMeters>")
            s?.heartRate?.takeIf { it > 0 }?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            s?.cadenceRpm?.takeIf { it > 0 }?.let { sb.append("<Cadence>").append(it).append("</Cadence>") }
            s?.powerW?.takeIf { it > 0 }?.let { sb.append("<Extensions><ns3:TPX><ns3:Watts>").append(it).append("</ns3:Watts></ns3:TPX></Extensions>") }
            sb.append("</Trackpoint>\n")
        }
        sb.append("        </Track>\n      </Lap>\n    </Activity>\n  </Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun cumulativeKm(track: List<GpsPoint>): List<Double> {
        val out = ArrayList<Double>(track.size)
        var d = 0.0
        track.forEachIndexed { i, p ->
            if (i > 0) d += haversineKm(track[i - 1], p)
            out += d
        }
        return out
    }

    /** Dateiname ohne Sonderzeichen, z. B. `2026-09-08_12-21_Testrunde`. */
    fun fileBaseName(ride: Ride, name: String?): String {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").format(ride.startTime)
        val clean = name?.trim()?.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")?.trim('-')?.take(40)
        return if (clean.isNullOrEmpty()) date else "${date}_$clean"
    }

    /** Packt Dateien (Name -> Inhalt) in ein ZIP. Doppelte Namen werden nummeriert. */
    fun zip(files: List<Pair<String, ByteArray>>, out: OutputStream) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                var unique = name
                var n = 2
                while (!used.add(unique)) unique = name.substringBeforeLast('.') + "-" + n++ + "." + name.substringAfterLast('.')
                zip.putNextEntry(ZipEntry(unique))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    fun zipBytes(files: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { zip(files, it) }.toByteArray()

    @Suppress("unused")
    private fun instant(t: LocalDateTime, zone: ZoneId): Instant = t.atZone(zone).toInstant()
}
