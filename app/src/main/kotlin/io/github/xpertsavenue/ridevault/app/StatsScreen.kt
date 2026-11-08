package io.github.xpertsavenue.ridevault.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xpertsavenue.ridevault.model.StoredRide
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Summen über eine Menge von Fahrten. */
private data class Totals(val count: Int, val km: Double, val seconds: Long, val climb: Double) {
    val avgKmh: Double? get() = if (seconds > 0 && km > 0) km / (seconds / 3600.0) else null
}

private fun totals(rides: List<StoredRide>) = Totals(
    count = rides.size,
    km = rides.sumOf { it.ride.summary.distanceKm ?: 0.0 },
    seconds = rides.sumOf { (it.ride.summary.durationSeconds ?: 0).toLong() },
    climb = rides.sumOf { it.ride.summary.climbM ?: 0.0 },
)

/** Statistik über die aktiven (nicht archivierten) Fahrten: Summen, Balkendiagramm und Bestwerte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: RidesViewModel, onOpenRide: (Long) -> Unit) {
    val all by vm.rides.collectAsState()
    val rides = remember(all) { all.filter { !it.archived } }
    if (rides.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val allTags by vm.tags.collectAsState()
    // Nur Tags anbieten, die an aktiven Fahrten hängen
    val usedTags = remember(rides, allTags) { allTags.filter { t -> rides.any { r -> r.tags.any { it.id == t.id } } } }
    var tagFilter by remember { mutableStateOf(setOf<Long>()) }
    val activeFilter = tagFilter.filter { id -> usedTags.any { it.id == id } }.toSet()
    // Mit Filter zählen Fahrten, die mindestens eines der gewählten Tags haben
    val filtered = remember(rides, activeFilter) {
        if (activeFilter.isEmpty()) rides else rides.filter { r -> r.tags.any { it.id in activeFilter } }
    }
    val years = remember(filtered) { filtered.map { it.ride.startTime.year }.distinct().sortedDescending() }
    // null = alle Jahre
    var year by remember { mutableStateOf<Int?>(null) }
    val chosen = year?.takeIf { it in years }
    val inPeriod = remember(filtered, chosen) { if (chosen == null) filtered else filtered.filter { it.ride.startTime.year == chosen } }
    val t = remember(inPeriod) { totals(inPeriod) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = chosen == null, onClick = { year = null }, label = { Text(stringResource(R.string.stats_period_all)) })
            years.forEach { y -> FilterChip(selected = chosen == y, onClick = { year = y }, label = { Text(y.toString()) }) }
        }
        if (usedTags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                usedTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in activeFilter,
                        onClick = { tagFilter = if (tag.id in tagFilter) tagFilter - tag.id else tagFilter + tag.id },
                        label = { Text(tag.name) },
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(tag.color))) },
                    )
                }
            }
        }
        if (inPeriod.isEmpty()) {
            Text(stringResource(R.string.stats_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        val tiles = buildList {
            add(stringResource(R.string.stats_rides) to t.count.toString())
            add(stringResource(R.string.label_distance) to "${fmt(t.km, 0)} km")
            add(stringResource(R.string.stats_time) to formatHoursMinutes(t.seconds))
            if (t.climb > 0) add(stringResource(R.string.label_climb) to "${fmt(t.climb, 0)} m")
            t.avgKmh?.let { add(stringResource(R.string.label_avg_speed) to "${fmt(it)} km/h") }
        }
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }

        // Balkendiagramm: Monate des gewählten Jahres bzw. Jahre bei "Alle"
        // Standard: Monate, sobald ein Jahr gewählt ist oder es nur ein Jahr gibt; sonst Jahre. Der Umschalter überschreibt das.
        var monthlyOverride by remember { mutableStateOf<Boolean?>(null) }
        val monthly = monthlyOverride ?: (chosen != null || years.size <= 1)
        val bars = remember(filtered, chosen, monthly) { barData(filtered, chosen, monthly) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (monthly) R.string.stats_per_month else R.string.stats_per_year),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    )
                    FilterChip(selected = monthly, onClick = { monthlyOverride = true }, label = { Text(stringResource(R.string.stats_by_months)) })
                    FilterChip(
                        selected = !monthly, onClick = { monthlyOverride = false },
                        label = { Text(stringResource(R.string.stats_by_years)) }, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                BarChart(bars)
            }
        }

        // Bestwerte, antippen öffnet die Fahrt
        val records = remember(inPeriod) {
            buildList {
                inPeriod.filter { it.ride.summary.distanceKm != null }.maxByOrNull { it.ride.summary.distanceKm!! }
                    ?.let { add(Triple(R.string.stats_longest, "${fmt(it.ride.summary.distanceKm!!)} km", it)) }
                inPeriod.filter { it.ride.summary.durationSeconds != null }.maxByOrNull { it.ride.summary.durationSeconds!! }
                    ?.let { add(Triple(R.string.stats_longest_time, formatDuration(it.ride.summary.durationSeconds!!), it)) }
                inPeriod.filter { it.ride.summary.maxSpeedKmh != null }.maxByOrNull { it.ride.summary.maxSpeedKmh!! }
                    ?.let { add(Triple(R.string.stats_fastest, "${fmt(it.ride.summary.maxSpeedKmh!!)} km/h", it)) }
                inPeriod.filter { (it.ride.summary.climbM ?: 0.0) > 0 }.maxByOrNull { it.ride.summary.climbM!! }
                    ?.let { add(Triple(R.string.stats_most_climb, "${fmt(it.ride.summary.climbM!!, 0)} m", it)) }
            }
        }
        if (records.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.stats_records), style = MaterialTheme.typography.titleSmall)
                    records.forEach { (label, value, ride) ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenRide(ride.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    (ride.name ?: ride.ride.startTime.format(dateFormatter())),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Bar(val label: String, val full: String, val km: Double, val highlighted: Boolean = true)

/**
 * Balken für das Diagramm. [monthly]: Monate des gewählten Jahres, ohne Jahr die letzten zwölf Monate bis zur jüngsten Fahrt.
 * Sonst ein Balken je Jahr; das gewählte Jahr ist hervorgehoben.
 */
private fun barData(rides: List<StoredRide>, year: Int?, monthly: Boolean): List<Bar> {
    fun km(list: List<StoredRide>) = list.sumOf { it.ride.summary.distanceKm ?: 0.0 }
    if (!monthly) {
        val byYear = rides.groupBy { it.ride.startTime.year }
        val first = byYear.keys.min()
        val last = byYear.keys.max()
        return (first..last).map { y -> Bar("’" + y.toString().takeLast(2), y.toString(), km(byYear[y].orEmpty()), year == null || year == y) }
    }
    val longMonth = { m: Int -> java.time.Month.of(m).getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()) }
    val narrow = { m: Int -> java.time.Month.of(m).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    if (year != null) {
        val byMonth = rides.filter { it.ride.startTime.year == year }.groupBy { it.ride.startTime.monthValue }
        return (1..12).map { m -> Bar(narrow(m), longMonth(m) + " " + year, km(byMonth[m].orEmpty())) }
    }
    val newest = rides.maxOf { it.ride.startTime }.let { java.time.YearMonth.of(it.year, it.monthValue) }
    val byMonth = rides.groupBy { java.time.YearMonth.of(it.ride.startTime.year, it.ride.startTime.monthValue) }
    return (11 downTo 0).map { back ->
        val ym = newest.minusMonths(back.toLong())
        Bar(narrow(ym.monthValue), longMonth(ym.monthValue) + " " + ym.year, km(byMonth[ym].orEmpty()))
    }
}

/** Runde Achsenwerte (1, 2, 5 mal Zehnerpotenz): liefert Schrittweite und Obergrenze, sodass etwa vier bis fünf Linien entstehen. */
private fun niceScale(max: Double): Pair<Double, Double> {
    val target = max.coerceAtLeast(1.0) / 4.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(target)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= target }
    val top = Math.ceil(max.coerceAtLeast(1.0) / step) * step
    return step to top
}

@Composable
private fun BarChart(bars: List<Bar>) {
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val (step, top) = niceScale(bars.maxOfOrNull { it.km } ?: 0.0)
    val ticks = (0..Math.round(top / step).toInt()).map { it * step }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Breite der y-Achse (Beschriftungen) – gemeinsam für Zeichnen und Berühren
    val axisW = remember(ticks, density) {
        val p = android.graphics.Paint().apply { textSize = with(density) { 11.sp.toPx() } }
        ticks.maxOf { p.measureText(fmt(it, 0)) } + with(density) { 8.dp.toPx() }
    }
    var touched by remember(bars) { mutableStateOf<Int?>(null) }
    // Die Balken wachsen beim Anzeigen und bei jeder Änderung (Jahr, Tags, Monate/Jahre) von unten hoch
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(bars) {
        grow.snapTo(0f)
        grow.animateTo(1f, androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }

    Canvas(
        Modifier.fillMaxWidth().height(190.dp).pointerInput(bars, axisW) {
            // Finger auf den Balken legen oder darüber ziehen: zeigt den Wert; senkrechtes Scrollen der Seite bricht es ab
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun indexAt(x: Float): Int? {
                    val slot = (size.width - axisW) / bars.size
                    return ((x - axisW) / slot).toInt().takeIf { x >= axisW && it in bars.indices }
                }
                touched = indexAt(down.position.x)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (change.isConsumed) { touched = null; return@awaitEachGesture }
                    touched = indexAt(change.position.x)
                } while (change.pressed)
                touched = null
            }
        },
    ) {
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            this.color = muted.toArgb()
        }
        val labelH = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val chartH = size.height - labelH - topPad
        val chartW = size.width - axisW
        // Hilfslinien und Werte der y-Achse
        label.textAlign = android.graphics.Paint.Align.RIGHT
        ticks.forEach { t ->
            val y = topPad + chartH - (t / top).toFloat() * chartH
            drawLine(grid, Offset(axisW, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(fmt(t, 0), axisW - 6.dp.toPx(), y + 4.dp.toPx(), label)
        }
        // Balken und Beschriftung der x-Achse
        val slot = chartW / bars.size
        val barW = slot * 0.62f
        label.textAlign = android.graphics.Paint.Align.CENTER
        bars.forEachIndexed { i, b ->
            val x = axisW + i * slot + (slot - barW) / 2
            val h = (b.km / top * chartH).toFloat() * grow.value
            val active = touched == i
            if (h > 0f) {
                drawRoundRect(
                    if (b.highlighted || active) color else color.copy(alpha = 0.4f),
                    Offset(x, topPad + chartH - h), Size(barW, h), CornerRadius(3.dp.toPx()),
                )
            }
            drawContext.canvas.nativeCanvas.drawText(b.label, axisW + i * slot + slot / 2, size.height - 4.dp.toPx(), label)
        }
        // Wert des berührten Balkens in einer Blase über dem Balken
        touched?.let { i ->
            val b = bars[i]
            val h = (b.km / top * chartH).toFloat()
            val text = b.full + ": " + fmt(b.km, if (b.km < 100) 1 else 0) + " km"
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 13.sp.toPx(); this.color = tipFg.toArgb(); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val w = tp.measureText(text) + 20.dp.toPx()
            val bh = 28.dp.toPx()
            val cx = axisW + i * slot + slot / 2
            val left = (cx - w / 2).coerceIn(axisW, size.width - w)
            val tipTop = (topPad + chartH - h - bh - 8.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(tipBg, Offset(left, tipTop), Size(w, bh), CornerRadius(8.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(text, left + 10.dp.toPx(), tipTop + bh / 2 + 5.dp.toPx(), tp)
        }
    }
}

private fun formatHoursMinutes(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    return if (h >= 100) "$h h" else "%d:%02d h".format(h, m)
}
