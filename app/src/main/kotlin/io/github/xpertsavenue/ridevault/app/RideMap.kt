package io.github.xpertsavenue.ridevault.app

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.background
import io.github.xpertsavenue.ridevault.model.StoredRide
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.xpertsavenue.ridevault.model.GpsPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.time.Duration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ---- Kachelquellen ---------------------------------------------------------------------------------------------

/** Esri World Imagery (Satellit). Kachel-Pfad ist z/y/x. */
private object EsriImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Tiles © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

/** CyclOSM: OpenStreetMap-Karte mit Fokus auf Radwege. */
private val CyclOsm = XYTileSource(
    "CyclOSM", 0, 20, 256, ".png",
    arrayOf(
        "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
        "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
        "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/",
    ),
    "© OpenStreetMap contributors, CyclOSM",
)

internal fun MapStyle.tileSource(): ITileSource = when (this) {
    MapStyle.STANDARD -> TileSourceFactory.MAPNIK
    MapStyle.CYCLING -> CyclOsm
    MapStyle.TERRAIN -> TileSourceFactory.OpenTopo
    MapStyle.SATELLITE -> EsriImagery
}

internal fun MapStyle.attribution(): String = when (this) {
    MapStyle.STANDARD -> "© OpenStreetMap"
    MapStyle.CYCLING -> "© OpenStreetMap, CyclOSM"
    MapStyle.TERRAIN -> "© OpenStreetMap, SRTM | OpenTopoMap"
    MapStyle.SATELLITE -> "© Esri, Maxar, Earthstar Geographics"
}

private fun initOsm(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        // Kachel-Cache dauerhaft im App-Speicher: bereits gesehene Karten gehen auch offline
        osmdroidBasePath = File(context.filesDir, "osmdroid")
        osmdroidTileCache = File(osmdroidBasePath, "tiles")
    }
}

// ---- Geschwindigkeit und Farben -----------------------------------------------------------------------------------

private const val BINS = 24

/** Farbe für 0..1: 0 = grün (langsam), 1 = rot (schnell). */
internal fun speedColor(t: Double): Int =
    AColor.HSVToColor(floatArrayOf((120.0 * (1.0 - t.coerceIn(0.0, 1.0))).toFloat(), 0.85f, 0.85f))

private fun haversineM(a: GpsPoint, b: GpsPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Geschwindigkeit je GPS-Punkt in km/h aus Abstand/Zeit, über fünf Punkte geglättet. */
internal fun gpsSpeeds(p: List<GpsPoint>): List<Double> {
    if (p.size < 2) return List(p.size) { 0.0 }
    val raw = DoubleArray(p.size)
    for (i in 1 until p.size) {
        val dt = Duration.between(p[i - 1].time, p[i].time).seconds.coerceAtLeast(1)
        raw[i] = haversineM(p[i - 1], p[i]) / dt * 3.6
    }
    raw[0] = raw[1]
    return List(p.size) { i ->
        val from = max(0, i - 2)
        val to = minOf(p.size - 1, i + 2)
        (from..to).map { raw[it] }.average()
    }
}

private class MapData(val geo: List<GeoPoint>, val speeds: List<Double>, val lo: Double, val hi: Double)

@Composable
private fun rememberMapData(track: List<GpsPoint>): MapData? = remember(track) {
    val valid = track.filter { it.latitude.isFinite() && it.longitude.isFinite() && (it.latitude != 0.0 || it.longitude != 0.0) }
    if (valid.size < 2) {
        null
    } else {
        val speeds = gpsSpeeds(valid)
        val s = speeds.sorted()
        // Perzentile 5..95, damit einzelne Ausreißer den Farbbereich nicht verzerren
        MapData(valid.map { GeoPoint(it.latitude, it.longitude) }, speeds, s[(s.size * 0.05).toInt()], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)])
    }
}

// ---- Karten-Ansicht ---------------------------------------------------------------------------------------------

/** Hält, was zuletzt gezeichnet wurde, damit nicht bei jeder Recomposition alles neu aufgebaut wird. */
private class MapState {
    var overlayKey: String? = null
    var zoomed = false
    var style: MapStyle? = null
}

/** Eine MapView samt Lebenszyklus (onResume/onPause/onDetach) – gemeinsam für Einzel- und Mehrfach-Karte. */
@Composable
private fun rememberManagedMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        initOsm(context)
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 3.0
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }
    return mapView
}

@Composable
private fun MapSurface(data: MapData, settings: AppSettings, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val state = remember { MapState() }
    val mapView = rememberManagedMapView()

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            if (state.style != settings.mapStyle) {
                state.style = settings.mapStyle
                map.setTileSource(settings.mapStyle.tileSource())
            }

            val key = "${settings.speedColors}|$primary"
            if (state.overlayKey != key) {
                state.overlayKey = key
                map.overlays.clear()
                val geo = data.geo
                if (settings.speedColors) {
                    val span = max(data.hi - data.lo, 1e-6)
                    fun bin(i: Int) = (((data.speeds[i] - data.lo) / span).coerceIn(0.0, 1.0) * (BINS - 1)).toInt()
                    // Aufeinanderfolgende Segmente gleicher Farbstufe zu einer Linie zusammenfassen
                    var start = 0
                    for (i in 1 until geo.size) {
                        if (i == geo.size - 1 || bin(i + 1) != bin(i)) {
                            map.overlays += polyline(geo.subList(start, i + 1), speedColor(bin(i).toDouble() / (BINS - 1)))
                            start = i
                        }
                    }
                } else {
                    map.overlays += polyline(geo, primary)
                }
                map.overlays += Marker(map).apply {
                    position = geo.first(); title = "Start"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays += Marker(map).apply {
                    position = geo.last(); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            }
            if (!state.zoomed) {
                state.zoomed = true
                val box = BoundingBox.fromGeoPoints(data.geo)
                // Erst zoomen, wenn die Karte eine Größe hat; Zoomstufe und Mitte selbst aus der Kartengröße berechnen
                val fit = {
                    if (map.width > 0 && map.height > 0) {
                        val margin = 96
                        val zoom = MapView.getTileSystem()
                            .getBoundingBoxZoom(box, (map.width - margin).coerceAtLeast(1), (map.height - margin).coerceAtLeast(1))
                        map.controller.setZoom(zoom.coerceIn(3.0, 19.0))
                        map.controller.setCenter(box.centerWithDateLine)
                    }
                }
                if (map.isLayoutOccurred && map.width > 0) {
                    map.post { fit() }
                } else {
                    map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
                }
            }
            map.invalidate()
        },
    )
}

private fun polyline(points: List<GeoPoint>, color: Int) = Polyline().apply {
    setPoints(points)
    outlinePaint.color = color
    outlinePaint.strokeWidth = 12f
    outlinePaint.strokeCap = Paint.Cap.ROUND
}

@Composable
private fun StyleChips(settings: AppSettings, onSettings: (AppSettings) -> Unit, modifier: Modifier = Modifier, showSpeed: Boolean = true) {
    // Volle Chipflächen statt Umrissen: lesbar auf hellen, dunklen und bunten Kartenhintergründen
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surface,
        labelColor = MaterialTheme.colorScheme.onSurface,
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    val elevation = FilterChipDefaults.filterChipElevation(elevation = 3.dp)
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            MapStyle.STANDARD to R.string.map_standard, MapStyle.CYCLING to R.string.map_cycling,
            MapStyle.TERRAIN to R.string.map_terrain, MapStyle.SATELLITE to R.string.map_satellite,
        ).forEach { (style, label) ->
            FilterChip(
                selected = settings.mapStyle == style,
                onClick = { onSettings(settings.copy(mapStyle = style)) },
                label = { Text(stringResource(label)) },
                colors = colors, elevation = elevation,
                border = null,
            )
        }
        if (showSpeed) {
            FilterChip(
                selected = settings.speedColors,
                onClick = { onSettings(settings.copy(speedColors = !settings.speedColors)) },
                label = { Text(stringResource(R.string.map_speed)) },
                colors = colors, elevation = elevation,
                border = null,
            )
        }
    }
}

@Composable
private fun Legend(data: MapData, settings: AppSettings) {
    if (settings.speedColors) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fmt(data.lo, 0), style = MaterialTheme.typography.labelSmall)
            Box(
                Modifier.weight(1f).height(8.dp).clip(CircleShape).background(
                    Brush.horizontalGradient((0 until BINS).map { Color(speedColor(it.toDouble() / (BINS - 1))) }),
                ),
            )
            Text("${fmt(data.hi, 0)} km/h", style = MaterialTheme.typography.labelSmall)
        }
    }
    Text(settings.mapStyle.attribution(), style = MaterialTheme.typography.labelSmall)
}

/**
 * Vorschau-Karte in der scrollenden Detailansicht. Sie ist absichtlich nicht bedienbar: Eine unsichtbare Fläche darüber
 * fängt nur Tipper ab (öffnet den Vollbild-Modus), Wischgesten laufen an die Seite weiter – die Seite scrollt also immer.
 */
@Composable
fun RideMapCard(track: List<GpsPoint>, settings: AppSettings, onSettings: (AppSettings) -> Unit, onOpenFullscreen: () -> Unit) {
    val data = rememberMapData(track) ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleChips(settings, onSettings)
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))) {
                MapSurface(data, settings, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().clickable(onClick = onOpenFullscreen))
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen), stringResource(R.string.nav_fullscreen),
                        Modifier.padding(8.dp),
                    )
                }
            }
            Legend(data, settings)
        }
    }
}

/** Höhe der Karte oben in der Detailansicht (ohne den Bereich unter der Titelleiste). */
internal val DetailMapHeight = 340.dp

/** true, wenn die Fahrt genug gültige GPS-Punkte für eine Karte hat. */
internal fun hasMapData(track: List<GpsPoint>): Boolean =
    track.count { it.latitude.isFinite() && it.longitude.isFinite() && (it.latitude != 0.0 || it.longitude != 0.0) } >= 2

/**
 * Karte oben in der Detailansicht: über die volle Breite, nicht bedienbar (Tippen öffnet den Vollbild-Modus). Beim Scrollen
 * schiebt sich der Inhalt (mit eigenem Hintergrund) über die Karte, die dabei langsamer wandert und ausgeblendet wird.
 * Das Ausblenden geschieht mit einer Fläche in Hintergrundfarbe über der Karte; die Karte selbst bekommt keine Transparenz
 * (das wäre bei einer MapView teuer und ruckelt).
 */
@Composable
fun RideMapHeader(
    track: List<GpsPoint>,
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onOpenFullscreen: () -> Unit,
    scroll: androidx.compose.foundation.ScrollState,
    /** Höhe der (durchsichtigen) Titelleiste samt Statusleiste: die Karte reicht darunter bis an den oberen Rand. */
    topInset: androidx.compose.ui.unit.Dp,
) {
    val data = rememberMapData(track) ?: return
    val heightDp = DetailMapHeight
    val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { heightDp.toPx() }
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier.fillMaxWidth().graphicsLayer {
            // Lesen des Scrollstands erst beim Zeichnen: die Seite wird beim Scrollen nicht neu aufgebaut
            translationY = scroll.value * 0.5f
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            // clipToBounds: die MapView darf nicht über ihre Fläche hinaus zeichnen
            Box(Modifier.fillMaxWidth().height(heightDp + topInset).clipToBounds()) {
                MapSurface(data, settings, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().clickable(onClick = onOpenFullscreen))
                // Abdunklung oben: Symbole und Titel der durchsichtigen Titelleiste bleiben auf jeder Karte lesbar
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topInset + 24.dp).background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)),
                    ),
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = topInset + 12.dp, end = 12.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 3.dp,
                ) {
                    Icon(painterResource(R.drawable.ic_fullscreen), stringResource(R.string.nav_fullscreen), Modifier.padding(8.dp))
                }
                StyleChips(settings, onSettings, Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 12.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(data, settings)
            }
        }
        // Ausblenden: Fläche in Hintergrundfarbe, die mit dem Scrollen deckender wird
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = (scroll.value / heightPx).coerceIn(0f, 1f) }.background(background),
        )
    }
}

/** Bedienbare Karte im Vollbild (Zoomen und Verschieben mit den Fingern). */
@Composable
fun RideMapFullscreen(track: List<GpsPoint>, settings: AppSettings, onSettings: (AppSettings) -> Unit, onClose: () -> Unit) {
    val data = rememberMapData(track) ?: return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.zIndex(1f).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.nav_close)) }
                    StyleChips(settings, onSettings, Modifier.weight(1f))
                }
                // clipToBounds: die Karte darf beim Zoomen nicht über die Kopfzeile (Schließen-Kreuz) zeichnen
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    MapSurface(data, settings, Modifier.fillMaxSize())
                }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Legend(data, settings) }
            }
        }
    }
}


// ---- Mehrere Fahrten auf einer Karte ----------------------------------------------------------------------------------

private class ColoredTrack(val color: Int, val points: List<GeoPoint>)

@Composable
private fun MultiMapSurface(tracks: List<ColoredTrack>, settings: AppSettings, modifier: Modifier) {
    val state = remember { MapState() }
    val mapView = rememberManagedMapView()
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            if (state.style != settings.mapStyle) {
                state.style = settings.mapStyle
                map.setTileSource(settings.mapStyle.tileSource())
            }

            val key = tracks.joinToString(",") { "${it.color}:${it.points.size}" }
            if (state.overlayKey != key) {
                state.overlayKey = key
                map.overlays.clear()
                tracks.forEach { map.overlays += polyline(it.points, it.color) }
            }
            val all = tracks.flatMap { it.points }
            if (!state.zoomed && all.size >= 2) {
                state.zoomed = true
                val box = BoundingBox.fromGeoPoints(all)
                val fit = {
                    if (map.width > 0 && map.height > 0) {
                        val margin = 96
                        val zoom = MapView.getTileSystem()
                            .getBoundingBoxZoom(box, (map.width - margin).coerceAtLeast(1), (map.height - margin).coerceAtLeast(1))
                        map.controller.setZoom(zoom.coerceIn(3.0, 19.0))
                        map.controller.setCenter(box.centerWithDateLine)
                    }
                }
                if (map.isLayoutOccurred && map.width > 0) map.post { fit() } else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
            }
            map.invalidate()
        },
    )
}

/** Vollbild-Karte mit mehreren Fahrten: je Fahrt eine eigene Farbe, darunter die Legende. */
@Composable
fun MultiRideMapDialog(rides: List<StoredRide>, defaults: AppSettings, @Suppress("UNUSED_PARAMETER") persist: (AppSettings) -> Unit, onClose: () -> Unit) {
    // Die Kartenart gilt nur für diese Ansicht; der Standard kommt aus den Einstellungen
    var styleOverride by remember { mutableStateOf<MapStyle?>(null) }
    val settings = defaults.copy(mapStyle = styleOverride ?: defaults.mapStyle)
    val onSettings: (AppSettings) -> Unit = { styleOverride = it.mapStyle }
    val accents = LocalAccents.current.colors
    val palette = remember(accents) { (accents + FixedTagColors).distinctBy { it.toArgb() } }
    val entries = remember(rides, palette) {
        rides.mapIndexed { i, r ->
            val pts = r.ride.track
                .filter { it.latitude.isFinite() && it.longitude.isFinite() && (it.latitude != 0.0 || it.longitude != 0.0) }
                .map { GeoPoint(it.latitude, it.longitude) }
            r to ColoredTrack(palette[i % palette.size].toArgb(), pts)
        }
    }
    // Stabile Liste: ändert sich nur, wenn sich die Fahrten ändern (nicht bei jeder Recomposition)
    val tracks = remember(entries) { entries.map { it.second }.filter { it.points.size >= 2 } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.zIndex(1f).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.nav_close)) }
                    Text(stringResource(R.string.map_multi_title, rides.size), style = MaterialTheme.typography.titleMedium)
                }
                StyleChips(settings, onSettings, Modifier.zIndex(1f).padding(horizontal = 12.dp), showSpeed = false)
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).clipToBounds()) {
                    MultiMapSurface(tracks, settings, Modifier.fillMaxSize())
                }
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEach { (r, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(t.color)))
                            Text(
                                r.name ?: r.ride.startTime.format(dateTimeFormatter()),
                                Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            r.ride.summary.distanceKm?.let { Text("${fmt(it)} km", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    Text(settings.mapStyle.attribution(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
