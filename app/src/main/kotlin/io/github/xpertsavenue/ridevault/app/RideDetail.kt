package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.model.StoredRide
import kotlin.math.max

@Composable
fun RideDetailScreen(
    stored: StoredRide?,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onExportGpx: () -> Unit,
    onExportTcx: () -> Unit,
    healthExported: Boolean,
    onHealth: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    /** Scrollstand der Seite; gehört dem Aufrufer, damit die Titelleiste beim Scrollen mitreagieren kann. */
    scroll: androidx.compose.foundation.ScrollState,
    /** 0..1: wie weit der Titel in die Titelleiste übergegangen ist (der Titel im Inhalt blendet dabei aus). */
    titleProgress: () -> Float,
    /** Meldet Lage (von oben im Inhalt) und Höhe des Titels unter der Karte, damit er in die Titelleiste wandern kann. */
    onTitleLayout: (top: Float, height: Float) -> Unit,
    /** Höhe der Titelleiste samt Statusleiste. Die Seite beginnt am oberen Bildrand; die Karte reicht unter die Leiste. */
    topInset: androidx.compose.ui.unit.Dp,
) {
    if (stored == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_loading)) }
        return
    }
    val r = stored.ride
    val s = r.summary
    var confirm by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    // Kartenart und Geschwindigkeitsfarben wählt man hier nur für diese Ansicht; der Standard steht in den Einstellungen
    var styleOverride by remember { mutableStateOf<MapStyle?>(null) }
    var speedOverride by remember { mutableStateOf<Boolean?>(null) }
    val mapSettings = settings.copy(mapStyle = styleOverride ?: settings.mapStyle, speedColors = speedOverride ?: settings.speedColors)
    val setMapSettings: (AppSettings) -> Unit = { styleOverride = it.mapStyle; speedOverride = it.speedColors }
    var containerTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Column(
        Modifier.fillMaxSize()
            .onGloballyPositioned { containerTop = it.positionInWindow().y }
            .verticalScroll(scroll),
    ) {
    // Karte ganz oben (volle Breite), beim Scrollen verblassend
    if (hasMapData(r.track)) {
        RideMapHeader(r.track, mapSettings, setMapSettings, onOpenFullscreen = { fullscreen = true }, scroll = scroll, topInset = topInset)
    } else {
        Spacer(Modifier.height(topInset)) // ohne Karte beginnt der Inhalt unter der Titelleiste
    }
    // Eigener Hintergrund: der Inhalt schiebt sich beim Scrollen über die Karte
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Titel unter der Karte: Name (falls vergeben) und Datum. Beim Scrollen blendet er aus, während derselbe Titel
        // in der Titelleiste einblendet (gegenläufig, gleicher Verlauf). Die Tags darunter bleiben und scrollen normal weg.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { c ->
                        // Lage im Inhalt = Lage im Fenster, abzüglich Anfang des Scrollbereichs, plus bereits gescrollte Strecke
                        onTitleLayout(c.positionInWindow().y - containerTop + scroll.value, c.size.height.toFloat())
                    }
                    .graphicsLayer { alpha = 1f - titleProgress() },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dateTime = r.startTime.format(dateTimeFormatter())
                Text(stored.name ?: dateTime, style = MaterialTheme.typography.headlineSmall)
                if (stored.name != null) {
                    Text(dateTime, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stored.tags.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp)) { TagChipRow(stored.tags) }
            }
        }

        // Farbige Kachel-Kanten: Gruppen gehören zusammen und passen zur Akzentfarbe (Index in LocalAccents)
        val acc = LocalAccents.current.colors
        val tiles = buildList {
            s.distanceKm?.let { add(Triple(stringResource(R.string.label_distance), "${fmt(it)} km", acc[0])) }
            s.durationSeconds?.let { add(Triple(stringResource(R.string.label_duration), formatDuration(it), acc[0])) }
            s.avgSpeedKmh?.let { add(Triple(stringResource(R.string.label_avg_speed), "${fmt(it)} km/h", acc[1])) }
            s.maxSpeedKmh?.let { add(Triple(stringResource(R.string.label_max_speed), "${fmt(it)} km/h", acc[1])) }
            s.climbM?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_climb), "${fmt(it, 0)} m", acc[2])) }
            s.altitudeM?.let { add(Triple(stringResource(R.string.label_altitude), "${fmt(it, 0)} m", acc[2])) }
            s.avgHeartRate?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_avg_hr), "$it bpm", acc[3])) }
            s.maxHeartRate?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_max_hr), "$it bpm", acc[3])) }
            s.avgCadenceRpm?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_avg_cadence), "$it rpm", acc[4])) }
            s.avgPowerW?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_avg_power), "$it W", acc[4])) }
            s.maxPowerW?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_max_power), "$it W", acc[4])) }
            s.calories?.takeIf { it > 0 }?.let { add(Triple(stringResource(R.string.label_calories), "$it kcal", acc[5])) }
            s.temperatureC?.let { add(Triple(stringResource(R.string.label_temperature), "${fmt(it, 0)} °C", acc[5])) }
        }
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, color) -> Tile(label, value, color, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }

        val samples = r.samples
        LineChart(stringResource(R.string.chart_speed), samples.map { it.speedKmh }, acc[1], showAxis = true)
        LineChart(stringResource(R.string.chart_altitude), samples.map { it.altitudeM?.toDouble() }, acc[2], showAxis = true)
        LineChart(stringResource(R.string.chart_hr), samples.map { it.heartRate?.toDouble() }, acc[3], ignoreZero = true)
        LineChart(stringResource(R.string.chart_cadence), samples.map { it.cadenceRpm?.toDouble() }, acc[4], ignoreZero = true)
        LineChart(stringResource(R.string.chart_power), samples.map { it.powerW?.toDouble() }, acc[4], ignoreZero = true)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExportGpx, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_gpx)) }
            OutlinedButton(onClick = onExportTcx, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_tcx)) }
        }
        OutlinedButton(onClick = onHealth, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (healthExported) R.string.health_remove else R.string.health_export))
        }
        OutlinedButton(onClick = onArchive, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (stored.archived) R.string.restore_from_archive else R.string.archive_action))
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete_ride)) }

        // Import-Herkunft: zentrierter Text am Ende, keine Kachel
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                stringResource(R.string.ride_imported_from, stored.deviceName ?: stringResource(R.string.ride_unknown_device)),
                style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.ride_imported_on, stored.importedAt.formatLocal(dateTimeFormatter())),
                style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center,
            )
        }
    }
    }

    if (fullscreen) RideMapFullscreen(r.track, mapSettings, setMapSettings, onClose = { fullscreen = false })

    if (confirm) {
        DeleteDialog(
            count = 1, canArchive = !stored.archived,
            onDelete = { confirm = false; onDelete() },
            onArchive = { confirm = false; onArchive() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun Tile(label: String, value: String, accent: Color, modifier: Modifier) {
    Card(modifier) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
            Column(Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** Einfaches Liniendiagramm; wird ausgeblendet, wenn keine brauchbaren Werte vorliegen. */
@Composable
private fun LineChart(title: String, raw: List<Double?>, color: Color, ignoreZero: Boolean = false, showAxis: Boolean = false) {
    val values = remember(raw) { downsample(raw.filterNotNull()) }
    if (values.size < 2 || (ignoreZero && values.all { it == 0.0 })) return
    val lo = values.min()
    val hi = values.max()
    val grid = MaterialTheme.colorScheme.outlineVariant
    ChartCard(if (showAxis) title else "$title   ${fmt(lo, 0)} – ${fmt(hi, 0)}") {
        Row(Modifier.fillMaxWidth().height(140.dp)) {
            if (showAxis) {
                Column(
                    Modifier.fillMaxHeight().padding(end = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    listOf(hi, (hi + lo) / 2, lo).forEach { Text(fmt(it, 0), style = MaterialTheme.typography.labelSmall) }
                }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val span = max(hi - lo, 1e-9)
                if (showAxis) {
                    listOf(0f, size.height / 2, size.height).forEach { y ->
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                }
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = size.width * i / (values.size - 1)
                    val y = size.height - ((v - lo) / span * size.height).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
        }
    }
}

private fun downsample(v: List<Double>, maxPoints: Int = 400): List<Double> {
    if (v.size <= maxPoints) return v
    val step = v.size.toDouble() / maxPoints
    return List(maxPoints) { v[(it * step).toInt()] }
}
