package io.github.xpertsavenue.ridevault.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class RideListOrganizerTest {
    private var nextId = 1L

    private fun ride(
        start: String, km: Double?, seconds: Int? = null, avg: Double? = null, max: Double? = null, device: String? = "BKV500",
    ) = StoredRide(
        nextId++,
        Ride("k", LocalDateTime.parse(start), RideSummary(durationSeconds = seconds, distanceKm = km, avgSpeedKmh = avg, maxSpeedKmh = max), emptyList(), emptyList()),
        Instant.EPOCH, device,
    )

    private val a = ride("2026-09-08T12:00:00", 0.7, 130, 18.5, 26.8, "BKV500")
    private val b = ride("2026-09-15T08:00:00", 1.2, 278, 16.0, 26.7, "BKV500")
    private val c = ride("2026-10-12T17:00:00", 4.1, 929, 16.0, 26.1, "Kross")
    private val d = ride("2026-10-01T17:00:00", null, null, null, null, null)
    private val all = listOf(a, b, c, d)

    private fun ids(g: List<RideGroup>) = g.flatMap { it.rides }.map { it.id }

    @Test
    fun `Standard ist Datum absteigend ohne Gruppen`() {
        val groups = organize(all, ListOptions())
        assertEquals(1, groups.size)
        assertEquals(GroupKey.None, groups[0].key)
        assertEquals(listOf(c, d, b, a).map { it.id }, ids(groups))
    }

    @Test
    fun `Datum aufsteigend`() {
        assertEquals(listOf(a, b, d, c).map { it.id }, ids(organize(all, ListOptions(SortKey.DATE, descending = false))))
    }

    @Test
    fun `Strecke absteigend, fehlende Werte immer zuletzt`() {
        assertEquals(listOf(c, b, a, d).map { it.id }, ids(organize(all, ListOptions(SortKey.DISTANCE, descending = true))))
        assertEquals(listOf(a, b, c, d).map { it.id }, ids(organize(all, ListOptions(SortKey.DISTANCE, descending = false))))
    }

    @Test
    fun `Gleichstand wird nach neuerer Fahrt aufgeloest`() {
        // b und c haben beide 16,0 km/h Durchschnitt: c ist neuer
        val g = organize(all, ListOptions(SortKey.AVG_SPEED, descending = true))
        assertEquals(listOf(a, c, b, d).map { it.id }, ids(g))
    }

    @Test
    fun `nach Monat gruppiert, neueste Gruppe zuerst`() {
        val groups = organize(all, ListOptions(groupBy = GroupBy.MONTH))
        assertEquals(
            listOf(GroupKey.Month(YearMonth.of(2026, 10)), GroupKey.Month(YearMonth.of(2026, 9))),
            groups.map { it.key },
        )
        assertEquals(listOf(c, d).map { it.id }, groups[0].rides.map { it.id })
        assertEquals(listOf(b, a).map { it.id }, groups[1].rides.map { it.id })
    }

    @Test
    fun `Gruppen folgen der Richtung bei Datum aufsteigend, sonst neueste zuerst`() {
        val asc = organize(all, ListOptions(SortKey.DATE, descending = false, groupBy = GroupBy.MONTH))
        assertEquals(YearMonth.of(2026, 9), (asc[0].key as GroupKey.Month).month)
        val byDistance = organize(all, ListOptions(SortKey.DISTANCE, descending = false, groupBy = GroupBy.MONTH))
        assertEquals(YearMonth.of(2026, 10), (byDistance[0].key as GroupKey.Month).month)
    }

    @Test
    fun `nach Jahr gruppiert`() {
        val groups = organize(all + ride("2025-12-31T10:00:00", 2.0), ListOptions(groupBy = GroupBy.YEAR))
        assertEquals(listOf(2026, 2025), groups.map { (it.key as GroupKey.Year).year })
    }

    @Test
    fun `nach Geraet gruppiert, unbekanntes Geraet zuletzt, Summe je Gruppe`() {
        val groups = organize(all, ListOptions(groupBy = GroupBy.DEVICE))
        assertEquals(listOf<String?>("BKV500", "Kross", null), groups.map { (it.key as GroupKey.Device).name })
        assertEquals(0.7 + 1.2, groups[0].totalKm, 1e-9)
        assertEquals(0.0, groups[2].totalKm, 1e-9) // Fahrt ohne Strecke zählt als 0
    }

    private fun named(r: StoredRide, name: String?, vararg tags: Tag) = r.copy(name = name, tags = tags.toList())

    private val road = Tag(1, "Rennrad", 0xFF1976D2.toInt())
    private val moped = Tag(2, "moped", 0xFFD32F2F.toInt())

    @Test
    fun `nach Name sortiert, ohne Namen zuletzt, Gross-Kleinschreibung egal`() {
        val rides = listOf(named(a, "Zeta"), named(b, "alpha"), named(c, null), named(d, "  "))
        assertEquals(listOf(b, a, c, d).map { it.id }, ids(organize(rides, ListOptions(SortKey.NAME, descending = false))))
        assertEquals(listOf(a, b, c, d).map { it.id }, ids(organize(rides, ListOptions(SortKey.NAME, descending = true))))
    }

    @Test
    fun `nach Tag gruppiert, Fahrt mit zwei Tags erscheint in beiden Gruppen, ohne Tag zuletzt`() {
        val rides = listOf(named(a, null, road), named(b, null, road, moped), named(c, null, moped), named(d, null))
        val groups = organize(rides, ListOptions(groupBy = GroupBy.TAG))

        // alphabetisch ohne Beachtung der Gross-/Kleinschreibung: moped, Rennrad, dann "kein Tag"
        assertEquals(listOf<Tag?>(moped, road, null), groups.map { (it.key as GroupKey.TagGroup).tag })
        assertEquals(listOf(c, b).map { it.id }, groups[0].rides.map { it.id })
        assertEquals(listOf(b, a).map { it.id }, groups[1].rides.map { it.id })
        assertEquals(listOf(d.id), groups[2].rides.map { it.id })
    }

    @Test
    fun `ohne getaggte Fahrten gibt es nur die Gruppe Kein Tag`() {
        val groups = organize(all, ListOptions(groupBy = GroupBy.TAG))
        assertEquals(1, groups.size)
        assertEquals(GroupKey.TagGroup(null), groups[0].key)
    }

    @Test
    fun `leere Liste ergibt eine leere Gruppe bzw keine Gruppen`() {
        assertEquals(emptyList(), organize(emptyList(), ListOptions(groupBy = GroupBy.MONTH)))
        assertEquals(1, organize(emptyList(), ListOptions()).size)
    }
}
