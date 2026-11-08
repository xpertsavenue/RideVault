package io.github.xpertsavenue.ridevault.model

import java.time.YearMonth
import java.time.ZoneOffset

enum class SortKey { DATE, NAME, DISTANCE, DURATION, AVG_SPEED, MAX_SPEED }

enum class GroupBy { NONE, MONTH, YEAR, DEVICE, TAG }

data class ListOptions(
    val sortKey: SortKey = SortKey.DATE,
    val descending: Boolean = true,
    val groupBy: GroupBy = GroupBy.NONE,
)

/** Schlüssel einer Gruppe; die Beschriftung (Sprache, Datumsformat) bestimmt die Oberfläche. */
sealed interface GroupKey {
    data object None : GroupKey
    data class Month(val month: YearMonth) : GroupKey
    data class Year(val year: Int) : GroupKey

    /** [name] null: Fahrten ohne bekannten Fahrradcomputer. */
    data class Device(val name: String?) : GroupKey

    /** [tag] null: Fahrten ohne Tag. */
    data class TagGroup(val tag: Tag?) : GroupKey
}

data class RideGroup(val key: GroupKey, val rides: List<StoredRide>) {
    val totalKm: Double get() = rides.sumOf { it.ride.summary.distanceKm ?: 0.0 }
}

private fun sortValue(r: StoredRide, key: SortKey): Double? {
    val s = r.ride.summary
    return when (key) {
        SortKey.DATE -> r.ride.startTime.toEpochSecond(ZoneOffset.UTC).toDouble()
        SortKey.DISTANCE -> s.distanceKm
        SortKey.DURATION -> s.durationSeconds?.toDouble()
        SortKey.AVG_SPEED -> s.avgSpeedKmh
        SortKey.MAX_SPEED -> s.maxSpeedKmh
        SortKey.NAME -> null // Namen werden als Text verglichen
    }
}

/** Fehlende Werte stehen immer am Ende, unabhängig von der Richtung. Gleichstand: neuere Fahrt zuerst. */
private fun comparator(o: ListOptions): Comparator<StoredRide> {
    val primary: Comparator<StoredRide> =
        if (o.sortKey == SortKey.NAME) {
            Comparator { a, b ->
                val na = a.name?.takeIf { it.isNotBlank() }
                val nb = b.name?.takeIf { it.isNotBlank() }
                when {
                    na == null && nb == null -> 0
                    na == null -> 1
                    nb == null -> -1
                    o.descending -> String.CASE_INSENSITIVE_ORDER.compare(nb, na)
                    else -> String.CASE_INSENSITIVE_ORDER.compare(na, nb)
                }
            }
        } else {
            Comparator { a, b ->
                val va = sortValue(a, o.sortKey)
                val vb = sortValue(b, o.sortKey)
                when {
                    va == null && vb == null -> 0
                    va == null -> 1
                    vb == null -> -1
                    o.descending -> vb.compareTo(va)
                    else -> va.compareTo(vb)
                }
            }
        }
    return primary.thenComparing { a, b -> b.ride.startTime.compareTo(a.ride.startTime) }
}

/**
 * Sortiert die Fahrten und teilt sie in Gruppen. Ohne Gruppierung gibt es genau eine Gruppe [GroupKey.None].
 * Bei Gruppierung nach Tag erscheint eine Fahrt mit mehreren Tags in jeder ihrer Gruppen.
 */
fun organize(rides: List<StoredRide>, options: ListOptions): List<RideGroup> {
    val sorted = rides.sortedWith(comparator(options))
    // Zeitgruppen: neueste zuerst; nur bei "nach Datum aufsteigend" die älteste zuerst
    val timeAscending = options.sortKey == SortKey.DATE && !options.descending
    return when (options.groupBy) {
        GroupBy.NONE -> listOf(RideGroup(GroupKey.None, sorted))
        GroupBy.MONTH -> sorted.groupBy { YearMonth.from(it.ride.startTime) }
            .toSortedMap(if (timeAscending) compareBy { it } else compareByDescending { it })
            .map { (m, list) -> RideGroup(GroupKey.Month(m), list) }
        GroupBy.YEAR -> sorted.groupBy { it.ride.startTime.year }
            .toSortedMap(if (timeAscending) compareBy { it } else compareByDescending { it })
            .map { (y, list) -> RideGroup(GroupKey.Year(y), list) }
        GroupBy.DEVICE -> sorted.groupBy { it.deviceName }
            .entries.sortedWith(
                compareBy<Map.Entry<String?, List<StoredRide>>> { it.key == null } // unbekannt zuletzt
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key.orEmpty() },
            )
            .map { RideGroup(GroupKey.Device(it.key), it.value) }
        GroupBy.TAG -> {
            val byTag = LinkedHashMap<Tag, MutableList<StoredRide>>()
            val untagged = ArrayList<StoredRide>()
            for (r in sorted) {
                if (r.tags.isEmpty()) untagged += r else r.tags.forEach { byTag.getOrPut(it) { ArrayList() } += r }
            }
            val groups = byTag.entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key.name })
                .map { RideGroup(GroupKey.TagGroup(it.key), it.value) }
            if (untagged.isEmpty()) groups else groups + RideGroup(GroupKey.TagGroup(null), untagged)
        }
    }
}
