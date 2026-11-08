package io.github.xpertsavenue.ridevault.export

import io.github.xpertsavenue.ridevault.model.GpsPoint
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideSummary
import io.github.xpertsavenue.ridevault.model.Sample
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportersTest {
    private val t0 = LocalDateTime.of(2026, 9, 8, 12, 21, 46)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun ride(withSensors: Boolean = true) = Ride(
        "dev", t0,
        RideSummary(durationSeconds = 100, distanceKm = 0.5, maxSpeedKmh = 30.0, calories = 12),
        (0 until 20).map { GpsPoint(t0.plusSeconds(5L * it), 52.52 + it * 0.0003, 13.40) },
        (0 until 40).map { Sample(altitudeM = 390 + it, heartRate = if (withSensors) 120 + it else 0, cadenceRpm = if (withSensors) 80 else 0, powerW = 0) },
    )

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `GPX ist wohlgeformt und hat alle Punkte in UTC`() {
        val doc = parse(Exporters.gpx(ride(), "Test & <Runde>", berlin))
        val pts = doc.getElementsByTagName("trkpt")
        assertEquals(20, pts.length)
        // 12:21:46 in Berlin (Sommerzeit, +02:00) = 10:21:46 UTC
        assertEquals("2026-09-08T10:21:46Z", doc.getElementsByTagName("time").item(1).textContent)
        assertEquals("Test & <Runde>", doc.getElementsByTagName("name").item(0).textContent)
        assertEquals("52.520000", pts.item(0).attributes.getNamedItem("lat").nodeValue)
    }

    @Test
    fun `GPX enthaelt Puls und Trittfrequenz nur wenn vorhanden`() {
        assertTrue(parse(Exporters.gpx(ride(withSensors = true), null, berlin)).getElementsByTagName("gpxtpx:hr").length > 0)
        assertEquals(0, parse(Exporters.gpx(ride(withSensors = false), null, berlin)).getElementsByTagName("gpxtpx:hr").length)
    }

    @Test
    fun `TCX ist wohlgeformt mit Runde, Distanz und Zeit`() {
        val doc = parse(Exporters.tcx(ride(), berlin))
        assertEquals(20, doc.getElementsByTagName("Trackpoint").length)
        assertEquals("100", doc.getElementsByTagName("TotalTimeSeconds").item(0).textContent)
        assertEquals("500.0", doc.getElementsByTagName("DistanceMeters").item(0).textContent)
        assertEquals("Biking", doc.getElementsByTagName("Activity").item(0).attributes.getNamedItem("Sport").nodeValue)
        // Distanz im letzten Punkt entspricht der Gesamtdistanz (auf die Gerätedistanz skaliert)
        val last = doc.getElementsByTagName("Trackpoint").item(19)
        assertTrue(last.textContent.contains("500.0"), last.textContent)
    }

    @Test
    fun `kombiniertes GPX hat eine Spur je Fahrt und laesst Fahrten ohne GPS aus`() {
        val noGps = Ride("dev", t0, RideSummary(), emptyList(), emptyList())
        val doc = parse(Exporters.gpxCombined(listOf(ride() to "Erste", noGps to "Leer", ride() to null), berlin))
        assertEquals(2, doc.getElementsByTagName("trk").length)
        assertEquals(40, doc.getElementsByTagName("trkpt").length)
        assertEquals("Erste", doc.getElementsByTagName("trk").item(0).childNodes.let { n -> (0 until n.length).map { n.item(it) }.first { it.nodeName == "name" }.textContent })
    }

    @Test
    fun `Dateiname ohne Sonderzeichen`() {
        assertEquals("2026-09-08_12-21", Exporters.fileBaseName(ride(), null))
        assertEquals("2026-09-08_12-21_Test-Runde-2", Exporters.fileBaseName(ride(), " Test/Runde:2 "))
    }

    @Test
    fun `ZIP enthaelt alle Dateien, doppelte Namen werden nummeriert`() {
        val bytes = Exporters.zipBytes(listOf("a.gpx" to "1".toByteArray(), "a.gpx" to "2".toByteArray(), "b.gpx" to "3".toByteArray()))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals(listOf("a.gpx", "a-2.gpx", "b.gpx"), names)
    }
}
