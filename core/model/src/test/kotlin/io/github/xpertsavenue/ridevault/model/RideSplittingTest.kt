package io.github.xpertsavenue.ridevault.model

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideSplittingTest {

    /** Echte Aufzeichnung eines BKV: 1800 GPS- und 1800 Messpunkte über 5 Wochen in einer einzigen "Fahrt". */
    private fun realRide(): Ride {
        fun lines(name: String) = javaClass.getResource("/$name")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        val gps = lines("bkv_multiday_gps.csv").map {
            val (t, la, lo) = it.split(",")
            GpsPoint(LocalDateTime.parse(t), la.toDouble(), lo.toDouble())
        }
        val samples = lines("bkv_multiday_samples.csv").map {
            val c = it.split(",")
            Sample(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toInt(), c[4].toDouble(), c[5].toInt(), c[6].toInt(), c[7].toInt())
        }
        return Ride(
            "dev", LocalDateTime.of(2026, 9, 8, 12, 21, 46),
            RideSummary(durationSeconds = 2757, distanceKm = 11.4, avgSpeedKmh = 14.9, maxSpeedKmh = 31.3),
            gps, samples,
        )
    }

    @Test
    fun `die echte Mehrtagesaufzeichnung wird in sechs Fahrten geteilt`() {
        val rides = realRide().splitIntoRides()

        assertEquals(
            listOf(
                "2026-09-08T12:21:46", "2026-09-15T08:28:29", "2026-09-17T08:25:43",
                "2026-09-17T11:22:21", "2026-10-01T17:17:43", "2026-10-12T17:20:01",
            ),
            rides.map { it.startTime.toString() },
        )
        // Das Stück vom 1.10. 18:28 (0,17 km, 12 Minuten im Stand = GPS-Jitter in der Garage) wurde verworfen
        assertTrue(rides.none { it.startTime == LocalDateTime.parse("2026-10-01T18:28:10") })
    }

    @Test
    fun `jede Fahrt liegt an genau einem Tag und die Strecken passen zum Geraet`() {
        val rides = realRide().splitIntoRides()
        rides.forEach { r -> assertEquals(1, r.track.map { it.time.toLocalDate() }.distinct().size, "Tag-Mix in ${r.startTime}") }

        val expected = listOf(0.66, 1.24, 1.26, 3.36, 0.57, 4.14)
        val km = rides.map { it.summary.distanceKm!! }
        expected.forEachIndexed { i, e -> assertTrue(abs(km[i] - e) < 0.05, "Fahrt $i: ${km[i]} km, erwartet $e") }
        assertEquals(11.2, km.sum(), 0.2) // Gerät meldet 11,4 km inkl. des verworfenen Stücks (~0,17 km)
    }

    @Test
    fun `Fahrzeit der Teile entspricht ungefaehr der des Geraets`() {
        val moving = realRide().splitIntoRides().sumOf { it.summary.durationSeconds!! }
        assertTrue(moving in 2400..2900, "Fahrzeit gesamt $moving s, Gerät: 2757 s")
    }

    @Test
    fun `Anstieg liegt nahe am Wert des Geraets und wird nicht durch Standrauschen aufgeblaeht`() {
        val climb = realRide().splitIntoRides().sumOf { it.summary.climbM!! }
        // Gerät meldet 155 m (inkl. des verworfenen Stücks); mit Rauschen im Stand wären es über 300 m
        assertTrue(climb in 120.0..260.0, "Anstieg gesamt $climb m")
    }

    @Test
    fun `Messpunkte werden ueber die Strecke zugeordnet und beginnen bei 0 km`() {
        val rides = realRide().splitIntoRides()
        val n = rides.sumOf { it.samples.size }
        assertTrue(n in 1165..1175, "zugeordnete Messpunkte: $n (erwartet ca. 1170 von 1800, ca. 630 im Stand verworfen)")
        rides.forEach { assertEquals(0.0, it.samples.first().distanceKm!!, 1e-9) }
        rides.forEach { r ->
            val s = r.summary
            assertTrue(s.maxSpeedKmh!! in 5.0..35.0)
            assertTrue(s.avgSpeedKmh!! in 3.0..30.0, "Durchschnitt ${s.avgSpeedKmh}")
        }
    }

    @Test
    fun `eine normale Fahrt behaelt die Zusammenfassung des Geraets`() {
        val t0 = LocalDateTime.of(2026, 9, 8, 12, 0, 0)
        val track = (0 until 100).map { GpsPoint(t0.plusSeconds(5L * it), 50.0 + it * 0.0003, 12.0) }
        val summary = RideSummary(durationSeconds = 500, distanceKm = 3.3, avgCadenceRpm = 80)
        val ride = Ride("d", t0, summary, track + track.last(), emptyList())

        val result = ride.splitIntoRides()
        assertEquals(1, result.size)
        assertEquals(summary, result[0].summary)
        assertEquals(100, result[0].track.size) // wiederholter Zeitstempel entfernt
    }

    @Test
    fun `Fahrt ohne brauchbare Spur geht nicht verloren`() {
        val ride = Ride("d", LocalDateTime.of(2026, 9, 8, 12, 0, 0), RideSummary(distanceKm = 1.0), emptyList(), emptyList())
        assertEquals(listOf(ride), ride.splitIntoRides())
    }

    @Test
    fun `Zeitsprung trennt Fahrten, Pause unter dem Limit nicht`() {
        val t0 = LocalDateTime.of(2026, 9, 8, 12, 0, 0)
        fun leg(start: LocalDateTime) = (0 until 60).map { GpsPoint(start.plusSeconds(5L * it), 50.0 + it * 0.0003, 12.0) }
        val shortBreak = Ride("d", t0, RideSummary(), leg(t0) + leg(t0.plusMinutes(20)), emptyList())
        assertEquals(1, shortBreak.splitIntoRides().size)
        val longBreak = Ride("d", t0, RideSummary(), leg(t0) + leg(t0.plusMinutes(90)), emptyList())
        assertEquals(2, longBreak.splitIntoRides().size)
    }
}
