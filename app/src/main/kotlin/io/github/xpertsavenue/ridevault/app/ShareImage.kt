package io.github.xpertsavenue.ridevault.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideSummary
import io.github.xpertsavenue.ridevault.model.StoredRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ---- Einstellungen des Bildes ---------------------------------------------------------------------------------------

enum class ShareFormat(val width: Int, val height: Int) {
    PORTRAIT(1080, 1350), SQUARE(1080, 1080), STORY(1080, 1920),
}

enum class ShareBackground { MAP, DARK, LIGHT, TRANSPARENT }

enum class ShareStat { DISTANCE, DURATION, AVG_SPEED, MAX_SPEED, CLIMB, ALTITUDE, AVG_HR, MAX_HR, AVG_CADENCE, AVG_POWER, MAX_POWER, CALORIES, TEMPERATURE }

enum class ShareChart { SPEED, ALTITUDE, HEART_RATE, CADENCE, POWER }

/** Was auf das Bild kommt. Die Voreinstellung ist ein schlichtes Hochformat mit Karte, Strecke und drei Kennzahlen. */
data class ShareImageOptions(
    val format: ShareFormat = ShareFormat.PORTRAIT,
    val background: ShareBackground = ShareBackground.MAP,
    val mapStyle: io.github.xpertsavenue.ridevault.app.MapStyle = io.github.xpertsavenue.ridevault.app.MapStyle.STANDARD,
    /** Strecke nach Geschwindigkeit einfärben (grün → rot), sonst in der Akzentfarbe. */
    val trackBySpeed: Boolean = true,
    val showTitle: Boolean = true,
    val showTags: Boolean = true,
    val showDevice: Boolean = false,
    val showStartEnd: Boolean = true,
    val showWatermark: Boolean = true,
    val stats: Set<ShareStat> = setOf(ShareStat.DISTANCE, ShareStat.DURATION, ShareStat.AVG_SPEED),
    val charts: Set<ShareChart> = setOf(ShareChart.SPEED, ShareChart.ALTITUDE),
)

/** Beschriftung (Ressource) einer Kennzahl. */
fun statLabelRes(stat: ShareStat): Int = when (stat) {
    ShareStat.DISTANCE -> R.string.label_distance
    ShareStat.DURATION -> R.string.label_duration
    ShareStat.AVG_SPEED -> R.string.label_avg_speed
    ShareStat.MAX_SPEED -> R.string.label_max_speed
    ShareStat.CLIMB -> R.string.label_climb
    ShareStat.ALTITUDE -> R.string.label_altitude
    ShareStat.AVG_HR -> R.string.label_avg_hr
    ShareStat.MAX_HR -> R.string.label_max_hr
    ShareStat.AVG_CADENCE -> R.string.label_avg_cadence
    ShareStat.AVG_POWER -> R.string.label_avg_power
    ShareStat.MAX_POWER -> R.string.label_max_power
    ShareStat.CALORIES -> R.string.label_calories
    ShareStat.TEMPERATURE -> R.string.label_temperature
}

fun chartLabelRes(chart: ShareChart): Int = when (chart) {
    ShareChart.SPEED -> R.string.chart_speed
    ShareChart.ALTITUDE -> R.string.chart_altitude
    ShareChart.HEART_RATE -> R.string.chart_hr
    ShareChart.CADENCE -> R.string.chart_cadence
    ShareChart.POWER -> R.string.chart_power
}

/** Wert einer Kennzahl samt Einheit oder null, wenn die Fahrt sie nicht hat. */
fun statValue(s: RideSummary, stat: ShareStat): String? = when (stat) {
    ShareStat.DISTANCE -> s.distanceKm?.let { "${fmt(it)} km" }
    ShareStat.DURATION -> s.durationSeconds?.let(::formatDuration)
    ShareStat.AVG_SPEED -> s.avgSpeedKmh?.let { "${fmt(it)} km/h" }
    ShareStat.MAX_SPEED -> s.maxSpeedKmh?.let { "${fmt(it)} km/h" }
    ShareStat.CLIMB -> s.climbM?.takeIf { it > 0 }?.let { "${fmt(it, 0)} m" }
    ShareStat.ALTITUDE -> s.altitudeM?.let { "${fmt(it, 0)} m" }
    ShareStat.AVG_HR -> s.avgHeartRate?.takeIf { it > 0 }?.let { "$it bpm" }
    ShareStat.MAX_HR -> s.maxHeartRate?.takeIf { it > 0 }?.let { "$it bpm" }
    ShareStat.AVG_CADENCE -> s.avgCadenceRpm?.takeIf { it > 0 }?.let { "$it rpm" }
    ShareStat.AVG_POWER -> s.avgPowerW?.takeIf { it > 0 }?.let { "$it W" }
    ShareStat.MAX_POWER -> s.maxPowerW?.takeIf { it > 0 }?.let { "$it W" }
    ShareStat.CALORIES -> s.calories?.takeIf { it > 0 }?.let { "$it kcal" }
    ShareStat.TEMPERATURE -> s.temperatureC?.let { "${fmt(it, 0)} °C" }
}

fun availableStats(r: Ride): List<ShareStat> = ShareStat.entries.filter { statValue(r.summary, it) != null }

private fun series(r: Ride, c: ShareChart): List<Double?> = r.samples.map {
    when (c) {
        ShareChart.SPEED -> it.speedKmh
        ShareChart.ALTITUDE -> it.altitudeM?.toDouble()
        ShareChart.HEART_RATE -> it.heartRate?.takeIf { v -> v > 0 }?.toDouble()
        ShareChart.CADENCE -> it.cadenceRpm?.takeIf { v -> v > 0 }?.toDouble()
        ShareChart.POWER -> it.powerW?.takeIf { v -> v > 0 }?.toDouble()
    }
}

fun availableCharts(r: Ride): List<ShareChart> = ShareChart.entries.filter { c -> series(r, c).count { it != null } >= 2 }

// ---- Zeichnen -------------------------------------------------------------------------------------------------------

/** Erzeugt das Bild, legt es im Cache ab und liefert die Freigabe-Adresse zum Teilen. */
suspend fun createShareImage(context: Context, stored: StoredRide, options: ShareImageOptions, accent: Int, palette: IntArray): Uri {
    val bitmap = renderShareImage(context, stored, options, accent, palette)
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // alte Bilder aufräumen
        val base = (stored.name?.takeIf { it.isNotBlank() } ?: stored.ride.startTime.toLocalDate().toString())
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        File(dir, "RideVault-$base.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

private const val PAD = 56f
private const val STAT_ROW = 124f
private const val CHART_TITLE = 40f
private const val MAX_ZOOM = 17

private fun worldX(lon: Double) = (lon + 180.0) / 360.0
private fun worldY(lat: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-85.05, 85.05)))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

suspend fun renderShareImage(context: Context, stored: StoredRide, o: ShareImageOptions, accent: Int, palette: IntArray): Bitmap =
    withContext(Dispatchers.Default) {
        val ride = stored.ride
        val w = o.format.width
        val h = o.format.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val transparent = o.background == ShareBackground.TRANSPARENT
        val light = o.background == ShareBackground.LIGHT
        val onMap = o.background == ShareBackground.MAP
        val bg = if (light) 0xFFF4F6F8.toInt() else 0xFF14181C.toInt()
        val primary = if (light) 0xFF14181C.toInt() else Color.WHITE
        val muted = if (light) 0xFF5B6670.toInt() else 0xFFB4BDC6.toInt()
        val shadow = transparent || onMap

        // ---- Inhalte vorbereiten
        val stats = ShareStat.entries.filter { it in o.stats }.mapNotNull { st -> statValue(ride.summary, st)?.let { st to it } }
        val charts = ShareChart.entries.filter { it in o.charts }.mapNotNull { ch ->
            val data = series(ride, ch)
            if (data.count { it != null } >= 2) ch to data else null
        }
        val rows = (stats.size + 2) / 3
        val statsH = rows * STAT_ROW
        var chartH = 170f
        // Reicht der Platz nicht, werden die Diagramme niedriger, damit die Karte mindestens ein Drittel behält
        val fixed = PAD + statsH + (if (charts.isNotEmpty()) 20f else 0f) + charts.size * CHART_TITLE + 70f
        if (charts.isNotEmpty()) {
            val room = h * 0.64f - fixed
            chartH = (room / charts.size).coerceIn(70f, 170f)
        }
        val chartsH = charts.size * (CHART_TITLE + chartH + 14f)
        val hasPanel = stats.isNotEmpty() || charts.isNotEmpty()
        val panelH = if (hasPanel) PAD + statsH + (if (charts.isNotEmpty()) 20f else 0f) + chartsH + 70f else 0f
        val mapH = h - panelH

        // ---- Hintergrund
        if (!transparent) {
            c.drawColor(bg)
        }

        // ---- Karte und Strecke
        val valid = ride.track.filter { it.latitude.isFinite() && it.longitude.isFinite() && (it.latitude != 0.0 || it.longitude != 0.0) }
        val topInset = if (o.showTitle || (o.showTags && stored.tags.isNotEmpty())) 220f else 90f
        val inner = RectF(80f, topInset, w - 80f, mapH - 90f)
        var attribution: String? = null
        var speedLo = 0.0
        var speedHi = 0.0
        if (valid.size >= 2 && inner.height() > 100f) {
            val wx = valid.map { worldX(it.longitude) }
            val wy = valid.map { worldY(it.latitude) }
            val minX = wx.min(); val maxX = wx.max(); val minY = wy.min(); val maxY = wy.max()
            val dx = max(maxX - minX, 2e-5)
            val dy = max(maxY - minY, 2e-5)
            val scale = min(inner.width() / dx, inner.height() / dy) // Pixel je Welteinheit
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2
            val ox = inner.centerX()
            val oy = inner.centerY()
            fun px(x: Double) = (ox + (x - cx) * scale).toFloat()
            fun py(y: Double) = (oy + (y - cy) * scale).toFloat()

            if (onMap) {
                val source = o.mapStyle.tileSource()
                val z = floor(log2(scale / 256.0)).toInt().coerceIn(2, min(MAX_ZOOM, source.maximumZoomLevel))
                val n = 2.0.pow(z)
                val tileSize = (scale / n).toFloat()
                val x0 = floor((cx + (0 - ox) / scale) * n).toInt().coerceAtLeast(0)
                val x1 = floor((cx + (w - ox) / scale) * n).toInt().coerceAtMost(n.toInt() - 1)
                val y0 = floor((cy + (0 - oy) / scale) * n).toInt().coerceAtLeast(0)
                val y1 = floor((cy + (mapH - oy) / scale) * n).toInt().coerceAtMost(n.toInt() - 1)
                if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 80 && source is OnlineTileSourceBase) {
                    val tiles = loadTiles(context, source, z, x0..x1, y0..y1)
                    c.save()
                    c.clipRect(0f, 0f, w.toFloat(), mapH)
                    val tp = Paint(Paint.FILTER_BITMAP_FLAG)
                    for ((key, tile) in tiles) {
                        val left = px(key.first.toDouble() / n)
                        val top = py(key.second.toDouble() / n)
                        c.drawBitmap(tile, null, RectF(left, top, left + tileSize + 1f, top + tileSize + 1f), tp)
                        tile.recycle()
                    }
                    c.restore()
                    if (tiles.isNotEmpty()) attribution = o.mapStyle.attribution()
                }
            }

            // Spur (höchstens ~2000 Punkte)
            val step = max(1, valid.size / 2000)
            val idx = (valid.indices step step).toMutableList().also { if (it.last() != valid.size - 1) it += valid.size - 1 }
            val xs = idx.map { px(wx[it]) }
            val ys = idx.map { py(wy[it]) }
            val speeds = gpsSpeeds(valid)
            val sorted = speeds.sorted()
            speedLo = sorted[(sorted.size * 0.05).toInt()]
            speedHi = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
            val span = (speedHi - speedLo).coerceAtLeast(0.1)

            val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                strokeWidth = 17f; color = if (light) 0xCCFFFFFF.toInt() else 0x99000000.toInt()
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 10f
            }
            val whole = Path().apply { moveTo(xs[0], ys[0]); for (i in 1 until xs.size) lineTo(xs[i], ys[i]) }
            c.drawPath(whole, casing)
            if (o.trackBySpeed) {
                val bins = 24
                fun bin(i: Int) = (((speeds[idx[i]] - speedLo) / span).coerceIn(0.0, 1.0) * (bins - 1)).toInt()
                var start = 0
                for (i in 1 until xs.size) {
                    val last = i == xs.size - 1
                    if (last || bin(i) != bin(start)) {
                        val seg = Path().apply { moveTo(xs[start], ys[start]); for (k in start + 1..i) lineTo(xs[k], ys[k]) }
                        line.color = speedColor(bin(start).toDouble() / (bins - 1))
                        c.drawPath(seg, line)
                        start = i
                    }
                }
            } else {
                line.color = accent
                c.drawPath(whole, line)
            }
            if (o.showStartEnd) {
                marker(c, xs.first(), ys.first(), 0xFF2E9E4F.toInt())
                marker(c, xs.last(), ys.last(), 0xFFD93A3A.toInt())
            }
        }

        // ---- Kopf: Name, Datum, Gerät, Tags
        val titleColor = if (onMap || transparent) Color.WHITE else primary
        val subColor = if (onMap || transparent) 0xE6FFFFFF.toInt() else muted
        if (onMap && (o.showTitle || (o.showTags && stored.tags.isNotEmpty()))) {
            val scrim = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 300f, 0xB0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, w.toFloat(), 300f, scrim)
        }
        var y = PAD + 46f
        if (o.showTitle) {
            val title = stored.name ?: ride.startTime.format(dateFormatter())
            c.text(title, PAD, y, textPaint(58f, titleColor, bold = true, shadow = shadow), w - 2 * PAD)
            y += 46f
            val sub = buildList {
                add(ride.startTime.format(dateFormatter()) + ", " + ride.startTime.format(timeFormatter()))
                if (o.showDevice) stored.deviceName?.let { add(it) }
            }.joinToString("  ·  ")
            if (stored.name != null || o.showDevice) c.text(sub, PAD, y, textPaint(32f, subColor, shadow = shadow), w - 2 * PAD)
            else c.text(ride.startTime.format(timeFormatter()), PAD, y, textPaint(32f, subColor, shadow = shadow), w - 2 * PAD)
            y += 30f
        }
        if (o.showTags && stored.tags.isNotEmpty()) {
            var x = PAD
            val tp = textPaint(28f, Color.WHITE, bold = true)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG)
            for (t in stored.tags) {
                val tw = tp.measureText(t.name)
                if (x + tw + 36f > w - PAD) break
                pill.color = t.color
                c.drawRoundRect(RectF(x, y, x + tw + 36f, y + 46f), 23f, 23f, pill)
                tp.color = if (luminance(t.color) > 0.6) Color.BLACK else Color.WHITE
                c.drawText(t.name, x + 18f, y + 32f, tp)
                x += tw + 36f + 12f
            }
        }

        // ---- Legende der Geschwindigkeitsfarben und Quellenangabe auf der Karte
        if (o.trackBySpeed && valid.size >= 2 && speedHi > speedLo) {
            val lx = PAD
            val ly = mapH - 52f
            val bar = Paint().apply {
                shader = LinearGradient(lx, 0f, lx + 240f, 0f, IntArray(7) { speedColor(it / 6.0) }, null, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(RectF(lx, ly, lx + 240f, ly + 12f), 6f, 6f, bar)
            val lp = textPaint(24f, if (onMap || transparent) Color.WHITE else muted, shadow = shadow)
            c.drawText("${fmt(speedLo, 0)}", lx, ly - 8f, lp)
            lp.textAlign = Paint.Align.RIGHT
            c.drawText("${fmt(speedHi, 0)} km/h", lx + 240f, ly - 8f, lp)
        }
        attribution?.let {
            val ap = textPaint(21f, Color.WHITE, shadow = true).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(it, w - 24f, mapH - 20f, ap)
        }

        // ---- Unterer Teil: Kennzahlen und Diagramme
        if (hasPanel) {
            if (!transparent) {
                c.drawRect(0f, mapH, w.toFloat(), h.toFloat(), Paint().apply { color = bg })
            }
            var py0 = mapH + PAD
            val colW = (w - 2 * PAD) / 3f
            stats.forEachIndexed { i, (st, value) ->
                val x = PAD + (i % 3) * colW
                val yy = py0 + (i / 3) * STAT_ROW
                val vp = textPaint(if (value.length > 9) 44f else 54f, if (i == 0) (if (transparent) accent else ensureContrast(accent, bg)) else primary, bold = true, shadow = transparent)
                c.text(value, x, yy + 52f, vp, colW - 16f)
                c.text(context.getString(statLabelRes(st)), x, yy + 90f, textPaint(27f, muted, shadow = transparent), colW - 16f)
            }
            py0 += statsH + if (charts.isNotEmpty()) 20f else 0f
            charts.forEachIndexed { i, (ch, data) ->
                val color = palette.getOrElse(1 + i % 4) { accent }
                c.text(context.getString(chartLabelRes(ch)), PAD, py0 + 28f, textPaint(27f, muted, shadow = transparent), w - 2 * PAD)
                drawChart(c, RectF(PAD, py0 + CHART_TITLE, w - PAD, py0 + CHART_TITLE + chartH), data, color, muted, transparent)
                py0 += CHART_TITLE + chartH + 14f
            }
        }
        if (o.showWatermark) {
            val wp = textPaint(24f, if (hasPanel) muted else Color.WHITE, bold = true, shadow = !hasPanel || transparent).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("RideVault", w - PAD, h - 30f, wp)
        }
        bmp
    }

private fun marker(c: Canvas, x: Float, y: Float, color: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE; c.drawCircle(x, y, 19f, p)
    p.color = color; c.drawCircle(x, y, 13f, p)
}

private fun luminance(color: Int): Double =
    (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

private fun textPaint(size: Float, color: Int, bold: Boolean = false, shadow: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    if (shadow) setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
}

/** Zeichnet Text; ist er breiter als erlaubt, wird er mit "…" gekürzt. */
private fun Canvas.text(s: String, x: Float, y: Float, paint: Paint, maxWidth: Float) {
    var t = s
    if (paint.measureText(t) > maxWidth) {
        val cut = paint.breakText(t, true, maxWidth - paint.measureText("…"), null)
        t = t.substring(0, cut).trimEnd() + "…"
    }
    drawText(t, x, y, paint)
}

/** Liniendiagramm mit gefüllter Fläche. Fehlende Werte werden übersprungen; die Reihe wird auf die Breite verdichtet. */
private fun drawChart(c: Canvas, r: RectF, data: List<Double?>, color: Int, muted: Int, transparent: Boolean) {
    val n = r.width().toInt().coerceAtLeast(2)
    val buckets = DoubleArray(n)
    val counts = IntArray(n)
    data.forEachIndexed { i, v ->
        if (v != null) {
            val b = (i.toLong() * (n - 1) / max(1, data.size - 1)).toInt()
            buckets[b] += v; counts[b]++
        }
    }
    val pts = (0 until n).filter { counts[it] > 0 }.map { it to buckets[it] / counts[it] }
    if (pts.size < 2) return
    val lo = pts.minOf { it.second }
    val hi = pts.maxOf { it.second }
    val span = (hi - lo).coerceAtLeast(1e-6)
    fun y(v: Double) = (r.bottom - 8f - ((v - lo) / span).toFloat() * (r.height() - 16f))
    val line = Path()
    val area = Path()
    pts.forEachIndexed { i, (b, v) ->
        val x = r.left + b.toFloat() / (n - 1) * r.width()
        if (i == 0) { line.moveTo(x, y(v)); area.moveTo(x, r.bottom); area.lineTo(x, y(v)) } else { line.lineTo(x, y(v)); area.lineTo(x, y(v)) }
    }
    area.lineTo(r.left + pts.last().first.toFloat() / (n - 1) * r.width(), r.bottom)
    area.close()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, r.top, 0f, r.bottom, (color and 0x00FFFFFF) or 0x66000000, (color and 0x00FFFFFF), Shader.TileMode.CLAMP)
    }
    c.drawPath(area, fill)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; this.color = color; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    c.drawPath(line, stroke)
    c.drawLine(r.left, r.bottom, r.right, r.bottom, Paint().apply { this.color = (muted and 0x00FFFFFF) or 0x55000000; strokeWidth = 2f })
    val lp = textPaint(23f, muted, shadow = transparent)
    c.drawText(fmt(hi, 0), r.left + 4f, r.top + 22f, lp)
    c.drawText(fmt(lo, 0), r.left + 4f, r.bottom - 12f, lp)
}

// ---- Kartenkacheln ----------------------------------------------------------------------------------------------------

/** Lädt die Kacheln (parallel, mit Zwischenspeicher im Cache-Ordner). Fehlende Kacheln fehlen im Ergebnis. */
private suspend fun loadTiles(
    context: Context,
    source: OnlineTileSourceBase,
    z: Int,
    xs: IntRange,
    ys: IntRange,
): Map<Pair<Int, Int>, Bitmap> = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "share-tiles").apply { mkdirs() }
    val gate = Semaphore(6)
    coroutineScope {
        xs.flatMap { x -> ys.map { y -> x to y } }.map { (x, y) ->
            async {
                gate.withPermit {
                    val cache = File(dir, "${source.name()}_${z}_${x}_$y.tile")
                    runCatching {
                        if (!cache.exists() || cache.length() == 0L) {
                            val url = URL(source.getTileURLString(MapTileIndex.getTileIndex(z, x, y)))
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8_000; readTimeout = 8_000
                                setRequestProperty("User-Agent", context.packageName)
                            }
                            try {
                                if (conn.responseCode == 200) conn.inputStream.use { input -> cache.outputStream().use { input.copyTo(it) } }
                            } finally {
                                conn.disconnect()
                            }
                        }
                        BitmapFactory.decodeFile(cache.path)?.let { (x to y) to it }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }
}
