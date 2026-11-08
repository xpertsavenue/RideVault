package io.github.xpertsavenue.ridevault.fit

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kleiner FIT-Schreiber nur für Tests (Little-Endian), erzeugt gültige Köpfe, Definitionen und Datennachrichten. */
private class FitWriter {
    private val body = ByteArrayOutputStream()

    private fun le(v: Long, size: Int) = ByteArray(size) { ((v shr (8 * it)) and 0xFF).toByte() }

    fun definition(localType: Int, global: Int, fields: List<Triple<Int, Int, Int>>, dev: List<Int> = emptyList()) {
        body.write(0x40 or (if (dev.isNotEmpty()) 0x20 else 0) or localType)
        body.write(0) // reserviert
        body.write(0) // Little-Endian
        body.write(le(global.toLong(), 2))
        body.write(fields.size)
        fields.forEach { (num, size, type) -> body.write(num); body.write(size); body.write(type) }
        if (dev.isNotEmpty()) {
            body.write(dev.size)
            dev.forEach { size -> body.write(0); body.write(size); body.write(0) }
        }
    }

    fun data(localType: Int, vararg values: Pair<Int, Long>) { // (Bytegröße, Wert)
        body.write(localType)
        values.forEach { (size, v) -> body.write(le(v, size)) }
    }

    fun raw(vararg bytes: Int) = bytes.forEach { body.write(it) }

    fun build(): ByteArray {
        val data = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(14); out.write(0x20)
        out.write(le(2100, 2)); out.write(le(data.size.toLong(), 4))
        out.write(".FIT".toByteArray()); out.write(le(0, 2)) // Kopf-CRC (nicht geprüft)
        out.write(data); out.write(le(0, 2)) // Datei-CRC (nicht geprüft)
        return out.toByteArray()
    }
}

class FitParserTest {
    // Basistypen: 0x84 uint16, 0x85 sint32, 0x86 uint32, 0x02 uint8, 0x01 sint8
    private val semi = { degrees: Double -> (degrees * 2147483648.0 / 180.0).toInt().toLong() and 0xFFFFFFFFL }
    private val start = 1_150_000_000L // FIT-Sekunden

    private fun record(w: FitWriter, ts: Long, lat: Double, lon: Double, alt: Double, hr: Int, speedMs: Double) =
        w.data(
            0,
            4 to ts, 4 to semi(lat), 4 to semi(lon), 2 to ((alt + 500) * 5).toLong(), 1 to hr.toLong(),
            4 to 123_456L, 2 to (speedMs * 1000).toLong(), 1 to 0xFFL, 1 to (-3L and 0xFF),
        )

    private fun sampleFile(): ByteArray {
        val w = FitWriter()
        w.definition(
            0, 20,
            listOf(
                Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(2, 2, 0x84), Triple(3, 1, 0x02),
                Triple(5, 4, 0x86), Triple(6, 2, 0x84), Triple(4, 1, 0x02), Triple(13, 1, 0x01),
            ),
        )
        record(w, start, 52.52, 13.40, 400.0, 120, 5.0)
        record(w, start + 5, 52.5201, 13.4002, 401.2, 125, 6.0)
        record(w, start + 10, 52.5202, 13.4004, 402.0, 130, 7.5)
        w.definition(
            1, 18,
            listOf(
                Triple(253, 4, 0x86), Triple(2, 4, 0x86), Triple(7, 4, 0x86), Triple(8, 4, 0x86), Triple(9, 4, 0x86),
                Triple(11, 2, 0x84), Triple(14, 2, 0x84), Triple(15, 2, 0x84), Triple(16, 1, 0x02), Triple(22, 2, 0x84),
            ),
        )
        w.data(1, 4 to start + 10, 4 to start, 4 to 12_000L, 4 to 10_000L, 4 to 5_000L, 2 to 42L, 2 to 6000L, 2 to 7500L, 1 to 125L, 2 to 12L)
        return w.build()
    }

    @Test
    fun `liest Datensaetze mit Umrechnung der Einheiten`() {
        val act = FitParser.parse(sampleFile())
        assertEquals(3, act.records.size)
        val r = act.records[1]
        assertEquals(start + 5, r.timestamp)
        assertTrue(abs(r.latitudeSemicircles!! * 180.0 / 2147483648.0 - 52.5201) < 1e-6)
        assertEquals(401.2, r.altitudeM!!, 0.2) // Auflösung 0,2 m
        assertEquals(125, r.heartRate)
        assertEquals(1234.56, r.distanceM!!, 1e-9)
        assertEquals(6.0, r.speedMs!!, 1e-9)
        assertEquals(-3, r.temperatureC)
        assertNull(r.power, "ungültiger Wert (0xFF) darf nicht als Wert gelten")
    }

    @Test
    fun `liest die Sitzung`() {
        val s = FitParser.parse(sampleFile()).sessions.single()
        assertEquals(start, s.startTime)
        assertEquals(12.0, s.totalElapsedSeconds!!, 1e-9)
        assertEquals(10.0, s.totalTimerSeconds!!, 1e-9)
        assertEquals(50.0, s.totalDistanceM!!, 1e-9)
        assertEquals(42, s.totalCalories)
        assertEquals(6.0, s.avgSpeedMs!!, 1e-9)
        assertEquals(7.5, s.maxSpeedMs!!, 1e-9)
        assertEquals(125, s.avgHeartRate)
        assertEquals(12, s.totalAscentM)
    }

    @Test
    fun `komprimierter Zeitstempel und Entwicklerfelder`() {
        val w = FitWriter()
        // Definition mit Entwicklerfeld (4 Byte), danach ein Datensatz, dann ein komprimierter Kopf (lokaler Typ 0, Offset +3)
        w.definition(0, 20, listOf(Triple(253, 4, 0x86), Triple(3, 1, 0x02)), dev = listOf(4))
        w.data(0, 4 to 1_000L, 1 to 100L, 4 to 0xAABBCCDDL)
        // Definition ohne Zeitstempelfeld für den komprimierten Kopf
        w.definition(1, 20, listOf(Triple(3, 1, 0x02)))
        // komprimierter Kopf: Bit7=1, lokaler Typ 1 (Bits 5-6), Offset 1000%32=8 -> +5 = 13
        w.raw(0x80 or (1 shl 5) or 13, 111)
        val recs = FitParser.parse(w.build()).records
        assertEquals(2, recs.size)
        assertEquals(1_000L, recs[0].timestamp)
        assertEquals(1_000L - (1_000L % 32) + 13, recs[1].timestamp)
        assertEquals(111, recs[1].heartRate)
    }

    @Test
    fun `abgeschnittene Datei liefert die vollstaendigen Nachrichten`() {
        val full = sampleFile()
        val cut = full.copyOf(full.size - 30) // mitten in der Sitzung
        val act = FitParser.parse(cut)
        assertEquals(3, act.records.size)
    }

    @Test
    fun `falsche Dateien werden abgelehnt`() {
        assertFailsWith<FitFormatException> { FitParser.parse(ByteArray(5)) }
        assertFailsWith<FitFormatException> { FitParser.parse(ByteArray(20) { 1 }) }
    }

    @Test
    fun `Umwandlung in eine Fahrt mit Zeitzone`() {
        val act = FitParser.parse(sampleFile())
        val ride = FitRides.toRide("dev", act, ZoneOffset.UTC)
        val expectedStart = LocalDateTime.ofEpochSecond(start + FitParser.FIT_EPOCH_OFFSET_SECONDS, 0, ZoneOffset.UTC)
        assertEquals(expectedStart, ride.startTime)
        assertEquals(3, ride.track.size)
        assertEquals(3, ride.samples.size)
        assertEquals(0.05, ride.summary.distanceKm!!, 1e-9)
        assertEquals(10, ride.summary.durationSeconds)
        assertEquals(21.6, ride.summary.avgSpeedKmh!!, 1e-9)
        assertEquals(27.0, ride.summary.maxSpeedKmh!!, 1e-9)
        assertEquals(1.23456, ride.samples.last().distanceKm!!, 1e-9)

        // dieselbe Zeit in einer anderen Zone
        val berlin = FitRides.toRide("dev", act, ZoneId.of("Europe/Berlin"))
        assertEquals(expectedStart.plusHours(if (expectedStart.month.value in 4..9) 2 else 1), berlin.startTime)
        assertNotNull(berlin.track.first())
    }

    @Test
    fun `Startzeit der Liste hat Vorrang`() {
        val forced = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals(forced, FitRides.toRide("dev", FitParser.parse(sampleFile()), ZoneOffset.UTC, startOverride = forced).startTime)
    }
}
