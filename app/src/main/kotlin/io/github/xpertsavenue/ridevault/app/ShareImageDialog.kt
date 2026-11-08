package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.model.StoredRide
import kotlinx.coroutines.launch

/** Auswahl, was auf das Bild kommt. "Teilen" erzeugt das Bild und öffnet das Teilen-Menü des Systems. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareImageDialog(
    stored: StoredRide,
    options: ShareImageOptions,
    onOptions: (ShareImageOptions) -> Unit,
    onShare: suspend (ShareImageOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val ride = stored.ride
    val statsAvailable = remember(ride) { availableStats(ride) }
    val chartsAvailable = remember(ride) { availableCharts(ride) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.share_failed)

    @Composable
    fun Section(title: String, content: @Composable () -> Unit) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }

    @Composable
    fun <T> Choice(current: T, items: List<Pair<T, String>>, onPick: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (value, label) ->
                FilterChip(selected = current == value, onClick = { onPick(value) }, label = { Text(label) }, enabled = !busy)
            }
        }
    }

    @Composable
    fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text(label) }, enabled = !busy)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.share_title)) },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onShare(options)
                            onDismiss()
                        } catch (e: Exception) {
                            error = failed + " " + (e.message ?: "")
                        }
                        busy = false
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.share_creating), Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(R.string.share_create))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Section(stringResource(R.string.share_section_format)) {
                    Choice(
                        options.format,
                        listOf(
                            ShareFormat.PORTRAIT to stringResource(R.string.share_format_portrait),
                            ShareFormat.SQUARE to stringResource(R.string.share_format_square),
                            ShareFormat.STORY to stringResource(R.string.share_format_story),
                        ),
                    ) { onOptions(options.copy(format = it)) }
                }
                Section(stringResource(R.string.share_section_background)) {
                    Choice(
                        options.background,
                        listOf(
                            ShareBackground.MAP to stringResource(R.string.share_bg_map),
                            ShareBackground.DARK to stringResource(R.string.share_bg_dark),
                            ShareBackground.LIGHT to stringResource(R.string.share_bg_light),
                            ShareBackground.TRANSPARENT to stringResource(R.string.share_bg_transparent),
                        ),
                    ) { onOptions(options.copy(background = it)) }
                }
                if (options.background == ShareBackground.MAP) {
                    Section(stringResource(R.string.settings_map)) {
                        Choice(
                            options.mapStyle,
                            listOf(
                                MapStyle.STANDARD to stringResource(R.string.map_standard),
                                MapStyle.CYCLING to stringResource(R.string.map_cycling),
                                MapStyle.TERRAIN to stringResource(R.string.map_terrain),
                                MapStyle.SATELLITE to stringResource(R.string.map_satellite),
                            ),
                        ) { onOptions(options.copy(mapStyle = it)) }
                    }
                }
                Section(stringResource(R.string.share_section_track)) {
                    Choice(
                        options.trackBySpeed,
                        listOf(true to stringResource(R.string.map_speed), false to stringResource(R.string.share_track_accent)),
                    ) { onOptions(options.copy(trackBySpeed = it)) }
                }
                Section(stringResource(R.string.share_section_content)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Toggle(stringResource(R.string.share_c_title), options.showTitle) { onOptions(options.copy(showTitle = it)) }
                        if (stored.tags.isNotEmpty()) {
                            Toggle(stringResource(R.string.share_c_tags), options.showTags) { onOptions(options.copy(showTags = it)) }
                        }
                        if (stored.deviceName != null) {
                            Toggle(stringResource(R.string.share_c_device), options.showDevice) { onOptions(options.copy(showDevice = it)) }
                        }
                        Toggle(stringResource(R.string.share_c_startend), options.showStartEnd) { onOptions(options.copy(showStartEnd = it)) }
                        Toggle(stringResource(R.string.share_c_watermark), options.showWatermark) { onOptions(options.copy(showWatermark = it)) }
                    }
                }
                if (statsAvailable.isNotEmpty()) {
                    Section(stringResource(R.string.share_section_stats)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            statsAvailable.forEach { st ->
                                Toggle(stringResource(statLabelRes(st)), st in options.stats) {
                                    onOptions(options.copy(stats = if (it) options.stats + st else options.stats - st))
                                }
                            }
                        }
                    }
                }
                if (chartsAvailable.isNotEmpty()) {
                    Section(stringResource(R.string.share_section_charts)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chartsAvailable.forEach { ch ->
                                Toggle(stringResource(chartLabelRes(ch)), ch in options.charts) {
                                    onOptions(options.copy(charts = if (it) options.charts + ch else options.charts - ch))
                                }
                            }
                        }
                    }
                }
                if (options.background == ShareBackground.MAP) {
                    Text(
                        stringResource(R.string.share_map_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}
