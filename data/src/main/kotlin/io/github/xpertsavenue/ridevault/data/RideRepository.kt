package io.github.xpertsavenue.ridevault.data

import io.github.xpertsavenue.ridevault.model.GpsPoint
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideKey
import io.github.xpertsavenue.ridevault.model.StoredRide
import io.github.xpertsavenue.ridevault.model.Sample
import io.github.xpertsavenue.ridevault.model.splitIntoRides
import io.github.xpertsavenue.ridevault.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant


class RideRepository(private val dao: RideDao) {

    /** Fahrten mit Namen und Tags; aktualisiert sich bei jeder Änderung an Fahrten, Tags oder Zuordnungen. */
    fun observeRides(): Flow<List<StoredRide>> =
        combine(dao.observeAll(), dao.observeTags(), dao.observeRideTags()) { rides, tags, links ->
            val tagById = tags.associate { it.id to Tag(it.id, it.name, it.color) }
            val tagsByRide = links.groupBy({ it.rideId }, { it.tagId }).mapValues { (_, ids) ->
                ids.mapNotNull(tagById::get).sortedBy { it.name.lowercase() }
            }
            rides.map {
                StoredRide(
                    it.id, Ride(it.deviceKey, it.startTime, it.summary, emptyList(), emptyList()),
                    Instant.ofEpochMilli(it.importedAt), it.deviceName, it.name, tagsByRide[it.id].orEmpty(), it.archived,
                )
            }
        }

    fun observeTags(): Flow<List<Tag>> = dao.observeTags().map { list -> list.map { Tag(it.id, it.name, it.color) } }

    /** Legt einen Tag an. Gibt es den Namen schon (Groß-/Kleinschreibung egal), wird der vorhandene Tag zurückgegeben. */
    suspend fun createTag(name: String, color: Int): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Tag-Name ist leer" }
        return dao.tagByName(clean)?.id ?: dao.insertTag(TagEntity(name = clean, color = color))
    }

    /** Benennt einen Tag um bzw. ändert die Farbe. Ein bereits vergebener Name wird abgelehnt (false). */
    suspend fun updateTag(tag: Tag): Boolean {
        val clean = tag.name.trim()
        if (clean.isEmpty()) return false
        val clash = dao.tagByName(clean)
        if (clash != null && clash.id != tag.id) return false
        dao.updateTag(tag.id, clean, tag.color)
        return true
    }

    suspend fun deleteTag(id: Long) = dao.deleteTag(id)

    /** Speichert Name (leer = kein Name) und Tags einer Fahrt. */
    suspend fun updateRideInfo(rideId: Long, name: String?, tagIds: Collection<Long>) =
        dao.setRideInfo(rideId, name?.trim()?.takeIf { it.isNotEmpty() }, tagIds)

    /** Tags für mehrere Fahrten gleichzeitig hinzufügen/entfernen. */
    suspend fun changeTags(rideIds: Collection<Long>, add: Collection<Long>, remove: Collection<Long>) {
        if (rideIds.isNotEmpty() && (add.isNotEmpty() || remove.isNotEmpty())) dao.changeTags(rideIds.toList(), add, remove)
    }

    suspend fun exists(key: RideKey): Boolean = dao.count(key.deviceKey, key.startTime) > 0

    suspend fun save(ride: Ride, deviceName: String? = null): Long = dao.save(
        RideEntity(
            deviceKey = ride.deviceKey, startTime = ride.startTime, summary = ride.summary,
            importedAt = System.currentTimeMillis(), deviceName = deviceName,
        ),
        ride.track.map { TrackPointEntity(rideId = 0, time = it.time, latitude = it.latitude, longitude = it.longitude) },
        ride.samples.map {
            SampleEntity(
                rideId = 0, temperatureC = it.temperatureC, speedKmh = it.speedKmh,
                gradientPercent = it.gradientPercent, altitudeM = it.altitudeM, distanceKm = it.distanceKm,
                heartRate = it.heartRate, cadenceRpm = it.cadenceRpm, powerW = it.powerW,
            )
        },
    )

    suspend fun load(id: Long): StoredRide? {
        val r = dao.ride(id) ?: return null
        val track = dao.track(id).map { GpsPoint(it.time, it.latitude, it.longitude) }
        val samples = dao.samples(id).map {
            Sample(it.temperatureC, it.speedKmh, it.gradientPercent, it.altitudeM, it.distanceKm, it.heartRate, it.cadenceRpm, it.powerW)
        }
        val tags = dao.tagsOf(id).map { Tag(it.id, it.name, it.color) }
        return StoredRide(
            id, Ride(r.deviceKey, r.startTime, r.summary, track, samples),
            Instant.ofEpochMilli(r.importedAt), r.deviceName, r.name, tags, r.archived,
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Archiviert Fahrten bzw. holt sie zurück. Archivierte Fahrten gelten beim Import weiter als bekannt. */
    suspend fun setArchived(ids: Collection<Long>, archived: Boolean) {
        if (ids.isNotEmpty()) dao.setArchived(ids.toList(), archived)
    }

    suspend fun allRideIds(): List<Long> = dao.allIds()

    /**
     * Teilt bereits gespeicherte Mehrtages-Aufzeichnungen (vor Einführung der Aufteilung importiert) in Einzelfahrten.
     * Importdatum und Gerät bleiben erhalten. Rückgabe: Anzahl der geteilten Fahrten.
     */
    suspend fun splitStoredMultiDayRides(): Int {
        var changed = 0
        for (id in dao.allIds()) {
            val old = dao.ride(id) ?: continue
            val stored = load(id) ?: continue
            val parts = stored.ride.splitIntoRides()
            if (parts.size <= 1) continue
            dao.replace(
                id,
                parts.map { p ->
                    Triple(
                        RideEntity(
                            deviceKey = p.deviceKey, startTime = p.startTime, summary = p.summary,
                            importedAt = old.importedAt, deviceName = old.deviceName, archived = old.archived,
                        ),
                        p.track.map { TrackPointEntity(rideId = 0, time = it.time, latitude = it.latitude, longitude = it.longitude) },
                        p.samples.map {
                            SampleEntity(
                                rideId = 0, temperatureC = it.temperatureC, speedKmh = it.speedKmh,
                                gradientPercent = it.gradientPercent, altitudeM = it.altitudeM, distanceKm = it.distanceKm,
                                heartRate = it.heartRate, cadenceRpm = it.cadenceRpm, powerW = it.powerW,
                            )
                        },
                    )
                },
            )
            changed++
        }
        return changed
    }
}
