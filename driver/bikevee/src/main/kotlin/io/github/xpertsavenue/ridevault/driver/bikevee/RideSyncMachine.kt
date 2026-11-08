package io.github.xpertsavenue.ridevault.driver.bikevee

import io.github.xpertsavenue.ridevault.device.SyncEvent
import io.github.xpertsavenue.ridevault.model.GpsPoint
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideKey
import io.github.xpertsavenue.ridevault.model.RideSummary
import io.github.xpertsavenue.ridevault.model.Sample
import io.github.xpertsavenue.ridevault.model.splitIntoRides
import java.time.LocalDateTime

class BikeveeProtocolException(message: String) : RuntimeException(message)

/** Eine Aktion der Zustandsmaschine; der Aufrufer führt sie **in der gelieferten Reihenfolge** aus. */
sealed interface SyncAction {
    data class Send(val frame: Frame) : SyncAction
    data class Emit(val event: SyncEvent) : SyncAction
}

/**
 * Ablauf der Fahrten-Synchronisation, ohne Bluetooth und ohne Zeit – rein eingabegetrieben,
 * deshalb vollständig testbar.
 *
 * Ablauf (siehe docs/bikevee-protokoll.md):
 * 1. `start()` -> Befehl 0x16 senden.
 * 2. Kopf (0x16) -> Fahrt bekannt? dann überspringen; sonst Datensätze sammeln.
 * 3. Jeder Datensatz 0x0E/0x18/0x02..0x0C wird bestätigt (0x19/0x1A/0x1C/0x1D nicht, wie im Original).
 * 4. 0x17 -> **zuerst** [SyncEvent.RideReceived] (Konsument speichert), **danach** Bestätigung 0x17,
 *    dann ggf. die nächste Fahrt mit 0x16 anfordern.
 */
class RideSyncMachine(
    private val deviceKey: String,
    private val isKnown: suspend (RideKey) -> Boolean,
) {
    var isFinished: Boolean = false
        private set

    private class Current(
        val start: LocalDateTime,
        val rideCount: Int,
        val totalRecords: Long,
    ) {
        var received = 0L
        val track = ArrayList<GpsPoint>()
        val samples = ArrayList<Sample>()
        var summary = RideSummary()
    }

    private var current: Current? = null
    private var announced = false

    fun start(): List<SyncAction> = listOf(SyncAction.Send(BikeveeCommands.requestRide()))

    suspend fun onFrame(frame: Frame): List<SyncAction> {
        if (isFinished) return emptyList()
        return when (val r = BikeveeParser.parse(frame)) {
            is Response.RideHeader -> onHeader(r)
            is Response.Gps -> record(frame.command) { c ->
                c.track += GpsPoint(r.time ?: c.start, r.latitude, r.longitude)
            }
            is Response.SampleRecord -> record(frame.command) { c ->
                c.samples += Sample(
                    temperatureC = r.temperatureC, speedKmh = r.speedKmh, gradientPercent = r.gradientPercent,
                    altitudeM = r.altitudeM, distanceKm = r.distanceKm, heartRate = r.heartRate,
                    cadenceRpm = r.cadenceRpm, powerW = r.powerW,
                )
            }
            is Response.Metric -> onMetric(r)
            is Response.RideEnd -> onEnd()
            else -> emptyList()
        }
    }

    private suspend fun onHeader(h: Response.RideHeader): List<SyncAction> {
        val out = ArrayList<SyncAction>()
        if (!announced) {
            announced = true
            out += SyncAction.Emit(SyncEvent.Started(h.rideCount))
        }
        val start = h.startTime
        if (h.rideCount == 0) return finish(out)
        if (start == null) throw BikeveeProtocolException("Fahrtkopf ohne gültige Startzeit")

        // Das Original quittiert bekannte Fahrten nicht und bricht ab; wir tun dasselbe, damit das Gerät nichts löscht.
        if (isKnown(RideKey(deviceKey, start))) {
            out += SyncAction.Emit(SyncEvent.RideSkipped(start))
            return finish(out)
        }
        current = Current(start, h.rideCount, h.totalRecords)
        out += SyncAction.Emit(SyncEvent.Progress(0, h.totalRecords))
        return out
    }

    private fun record(command: Int, add: (Current) -> Unit): List<SyncAction> {
        val c = current ?: return emptyList()
        add(c)
        c.received++
        return listOf(
            SyncAction.Emit(SyncEvent.Progress(c.received, c.totalRecords)),
            SyncAction.Send(BikeveeCommands.ack(command)),
        )
    }

    private fun onMetric(m: Response.Metric): List<SyncAction> {
        val c = current ?: return emptyList()
        val s = c.summary
        c.summary = when (m.command) {
            Cmd.SUMMARY_DURATION -> s.copy(durationSeconds = m.a.toInt())
            Cmd.SUMMARY_DISTANCE -> s.copy(distanceKm = m.a)
            Cmd.SUMMARY_AVG_SPEED -> s.copy(avgSpeedKmh = m.a)
            Cmd.SUMMARY_MAX_SPEED -> s.copy(maxSpeedKmh = m.a)
            Cmd.SUMMARY_TEMPERATURE -> s.copy(temperatureC = m.a)
            Cmd.SUMMARY_CLIMB -> s.copy(climbM = m.a)
            Cmd.SUMMARY_ALTITUDE -> s.copy(altitudeM = m.a)
            Cmd.SUMMARY_GRADIENT -> s.copy(avgGradientPercent = m.a.toInt(), maxGradientPercent = m.b?.toInt())
            Cmd.SUMMARY_HEART_RATE -> s.copy(avgHeartRate = m.a.toInt(), maxHeartRate = m.b?.toInt())
            Cmd.SUMMARY_CADENCE -> s.copy(avgCadenceRpm = m.a.toInt(), maxCadenceRpm = m.b?.toInt())
            Cmd.SUMMARY_CALORIES -> s.copy(calories = m.a.toInt())
            Cmd.SUMMARY_POWER -> s.copy(avgPowerW = m.a.toInt(), maxPowerW = m.b?.toInt())
            else -> s // Gesamtwerte (Odometer) gehören nicht zur Fahrt
        }
        // Original: 0x02..0x0C werden bestätigt, 0x19/0x1A/0x1C/0x1D nicht.
        return if (m.command in Cmd.SUMMARY_DURATION..Cmd.TOTAL_CLIMB) {
            listOf(SyncAction.Send(BikeveeCommands.ack(m.command)))
        } else {
            emptyList()
        }
    }

    private fun onEnd(): List<SyncAction> {
        val c = current ?: return emptyList()
        current = null
        val ride = Ride(deviceKey, c.start, c.summary, c.track.toList(), c.samples.toList())
        // Der BKV sammelt Fahrten über mehrere Tage in einem Block: in Einzelfahrten teilen, ALLE speichern, erst dann bestätigen.
        val out = ArrayList<SyncAction>()
        ride.splitIntoRides().forEach { out += SyncAction.Emit(SyncEvent.RideReceived(it)) }
        out += SyncAction.Send(BikeveeCommands.ack(Cmd.RIDE_END))
        // Wie das Original: bei mehr als einer gespeicherten Fahrt die nächste anfordern.
        return if (c.rideCount > 1) {
            out + SyncAction.Send(BikeveeCommands.requestRide())
        } else {
            finish(out)
        }
    }

    private fun finish(out: MutableList<SyncAction>): List<SyncAction> {
        isFinished = true
        out += SyncAction.Emit(SyncEvent.Finished)
        return out
    }
}
