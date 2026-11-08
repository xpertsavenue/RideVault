package io.github.xpertsavenue.ridevault.model

import java.time.LocalDateTime

/** Eindeutige Identität einer Fahrt: Gerät + Startzeit (Gerätezeit, ohne Zeitzone). */
data class RideKey(val deviceKey: String, val startTime: LocalDateTime)

data class GpsPoint(
    val time: LocalDateTime,
    val latitude: Double,
    val longitude: Double,
)

/** Ein Messpunkt. Alle Werte metrisch; fehlende Werte sind null. */
data class Sample(
    val temperatureC: Double? = null,
    val speedKmh: Double? = null,
    val gradientPercent: Double? = null,
    val altitudeM: Int? = null,
    val distanceKm: Double? = null,
    val heartRate: Int? = null,
    val cadenceRpm: Int? = null,
    val powerW: Int? = null,
)

data class RideSummary(
    val durationSeconds: Int? = null,
    val distanceKm: Double? = null,
    val avgSpeedKmh: Double? = null,
    val maxSpeedKmh: Double? = null,
    val temperatureC: Double? = null,
    val climbM: Double? = null,
    val altitudeM: Double? = null,
    val avgGradientPercent: Int? = null,
    val maxGradientPercent: Int? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val avgCadenceRpm: Int? = null,
    val maxCadenceRpm: Int? = null,
    val calories: Int? = null,
    val avgPowerW: Int? = null,
    val maxPowerW: Int? = null,
)

data class Ride(
    val deviceKey: String,
    val startTime: LocalDateTime,
    val summary: RideSummary,
    val track: List<GpsPoint>,
    val samples: List<Sample>,
) {
    val key: RideKey get() = RideKey(deviceKey, startTime)
}
