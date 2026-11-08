package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.device.LiveData
import io.github.xpertsavenue.ridevault.device.RideState
import java.util.Locale

/** Alle Werte, die der Live-Bildschirm zeigen kann. [value] ist null, wenn das Gerät den Wert nicht meldet. */
enum class LiveField(val id: String, val label: Int, val unit: String, val value: (LiveData) -> String?) {
    SPEED("speed", R.string.live_f_speed, "km/h", { d -> d.speedKmh?.let { f1(it) } }),
    AVG_SPEED("avg_speed", R.string.live_f_avg_speed, "km/h", { d -> d.avgSpeedKmh?.let { f1(it) } }),
    MAX_SPEED("max_speed", R.string.live_f_max_speed, "km/h", { d -> d.maxSpeedKmh?.let { f1(it) } }),
    TIME("time", R.string.live_f_time, "", { d -> d.timeSec?.let { hms(it) } }),
    DISTANCE("distance", R.string.live_f_distance, "km", { d -> d.distanceKm?.let { String.format(Locale.getDefault(), "%.2f", it) } }),
    HEART_RATE("hr", R.string.live_f_hr, "bpm", { d -> d.heartRate?.toString() }),
    AVG_HEART_RATE("avg_hr", R.string.live_f_avg_hr, "bpm", { d -> d.avgHeartRate?.toString() }),
    MAX_HEART_RATE("max_hr", R.string.live_f_max_hr, "bpm", { d -> d.maxHeartRate?.toString() }),
    CADENCE("cadence", R.string.live_f_cadence, "rpm", { d -> d.cadence?.toString() }),
    AVG_CADENCE("avg_cadence", R.string.live_f_avg_cadence, "rpm", { d -> d.avgCadence?.toString() }),
    POWER("power", R.string.live_f_power, "W", { d -> d.power?.toString() }),
    AVG_POWER("avg_power", R.string.live_f_avg_power, "W", { d -> d.avgPower?.toString() }),
    MAX_POWER("max_power", R.string.live_f_max_power, "W", { d -> d.maxPower?.toString() }),
    ALTITUDE("altitude", R.string.live_f_altitude, "m", { d -> d.altitudeM?.let { String.format(Locale.getDefault(), "%.0f", it) } }),
    SLOPE("slope", R.string.live_f_slope, "%", { d -> d.slopePercent?.let { f1(it) } }),
    ASCENT("ascent", R.string.live_f_ascent, "m", { d -> d.ascentM?.toString() });

    companion object {
        val defaults = listOf(SPEED, DISTANCE, TIME, HEART_RATE, CADENCE, POWER)
    }
}

private fun f1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
private fun hms(sec: Long) = String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)

/** Stand des Live-Bildschirms. [fields] sind die gewählten Kacheln in ihrer Reihenfolge. */
data class LiveUiState(
    val data: LiveData? = null,
    /** Das Gerät hat auf mehrere Abfragen nicht geantwortet. */
    val failed: Boolean = false,
    val fields: List<String> = LiveField.defaults.map { it.id },
)

/** Livewerte des Geräts als Kacheln; im Anpassungsmodus wählt man die Werte per Chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(ui: LiveUiState, customizing: Boolean, onFields: (List<String>) -> Unit) {
    // Display anlassen, solange der Bildschirm offen ist (wie ein Fahrradcomputer)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val d = ui.data
        when {
            ui.failed -> Text(stringResource(R.string.live_unsupported), color = MaterialTheme.colorScheme.error)
            d == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stringResource(R.string.live_waiting))
            }
            else -> {
                val state = when (d.rideState) {
                    RideState.RIDING -> stringResource(R.string.live_state_riding)
                    RideState.PAUSED -> stringResource(R.string.live_state_paused)
                    RideState.IDLE -> stringResource(R.string.live_state_idle)
                    null -> null
                }
                val navi = d.navigating?.let { if (it) stringResource(R.string.dash_navi_on) else stringResource(R.string.dash_navi_off) }
                val line = listOfNotNull(state, navi).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (customizing) {
            Text(stringResource(R.string.live_customize_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveField.entries.forEach { f ->
                    val on = f.id in ui.fields
                    FilterChip(
                        selected = on,
                        onClick = { onFields(if (on) ui.fields - f.id else ui.fields + f.id) },
                        label = { Text(stringResource(f.label)) },
                    )
                }
            }
        }
        val tiles = ui.fields.mapNotNull { id -> LiveField.entries.firstOrNull { it.id == id } }
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { f ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(f.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                (ui.data?.let { f.value(it) } ?: "–") + if (f.unit.isNotEmpty()) " ${f.unit}" else "",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
