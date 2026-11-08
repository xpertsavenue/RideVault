package io.github.xpertsavenue.ridevault.app

import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import java.time.ZoneId
import io.github.xpertsavenue.ridevault.export.Exporters
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import android.content.Intent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.toArgb
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xpertsavenue.ridevault.ble.BlePermissions
import io.github.xpertsavenue.ridevault.device.Capability
import io.github.xpertsavenue.ridevault.model.StoredRide

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as RideVaultApp).settings
        setContent {
            val settings by store.state.collectAsState()
            RideVaultTheme(settings) { AppRoot(store) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(store: SettingsStore, connectVm: ConnectViewModel = viewModel(), ridesVm: RidesViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val settings by store.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var pagesHelp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var liveCustomize by remember { mutableStateOf(false) }
    var routesHelp by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val routeFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) connectVm.sendRoute(uri) }
    var showEdit by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    val allTags by ridesVm.tags.collectAsState()
    val openId by ridesVm.selectedId.collectAsState()
    val detail by ridesVm.selected.collectAsState()
    val selection by ridesVm.selection.collectAsState()
    val allRides by ridesVm.visibleRides.collectAsState()
    val showArchive by ridesVm.showArchive.collectAsState()
    val pendingDelete by ridesVm.pendingDelete.collectAsState()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val mapRides by ridesVm.mapRides.collectAsState()
    val connectState by connectVm.state.collectAsState()
    var showDeviceSettings by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var showSelectionTags by remember { mutableStateOf(false) }
    var showShareImage by remember { mutableStateOf(false) }
    var shareOptions by remember { mutableStateOf(ShareImageOptions()) }
    val shareAccent = MaterialTheme.colorScheme.primary.toArgb()
    val sharePalette = LocalAccents.current.colors.map { it.toArgb() }.toIntArray()
    val context = LocalContext.current
    val activity = context as? Activity
    val app = context.applicationContext as RideVaultApp
    val scope = rememberCoroutineScope()
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Ergebnis eines Exports/Backups kurz einblenden
    fun report(okMsg: Int = R.string.export_done, block: suspend () -> Unit) {
        scope.launch {
            val msg = runCatching { block() }.fold(
                onSuccess = { context.getString(okMsg) },
                onFailure = { context.getString(R.string.export_failed, it.message ?: "") },
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    val zone = ZoneId.systemDefault()
    // Health Connect: nur auf Wunsch; vorher Verfügbarkeit und Berechtigungen prüfen
    val health = app.health
    val healthExported by health.exported.collectAsState()
    var pendingHealth by remember { mutableStateOf<Pair<Int, suspend () -> Unit>?>(null) }
    val healthPermLauncher = rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { granted ->
        val pending = pendingHealth
        pendingHealth = null
        if (pending != null) {
            if (granted.containsAll(health.permissions)) report(pending.first, pending.second)
            else Toast.makeText(context, context.getString(R.string.health_denied), Toast.LENGTH_LONG).show()
        }
    }
    fun runHealth(okMsg: Int, action: suspend () -> Unit) {
        when (health.availability()) {
            HealthExporter.Availability.MISSING -> Toast.makeText(context, context.getString(R.string.health_missing), Toast.LENGTH_LONG).show()
            HealthExporter.Availability.UPDATE -> Toast.makeText(context, context.getString(R.string.health_update), Toast.LENGTH_LONG).show()
            HealthExporter.Availability.OK -> scope.launch {
                val missing = runCatching { health.missingPermissions() }.getOrElse { setOf("?") }
                if (missing.isEmpty()) report(okMsg, action)
                else { pendingHealth = okMsg to action; healthPermLauncher.launch(health.permissions) }
            }
        }
    }
    val gpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = detail
        if (uri != null && d != null) report { DataTransfer.write(context, uri, Exporters.gpx(d.ride, d.name, zone).toByteArray()) }
    }
    val tcxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = detail
        if (uri != null && d != null) report { DataTransfer.write(context, uri, Exporters.tcx(d.ride, zone).toByteArray()) }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) report { DataTransfer.exportAllZip(app, uri) }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) report { DataTransfer.backup(app, uri) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; showRestoreConfirm = true }
    }
    val selZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) report { DataTransfer.exportRidesZip(context, uri, ridesVm.loadSelected(), zone) }
    }
    val selGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) report { DataTransfer.exportRidesCombinedGpx(context, uri, ridesVm.loadSelected(), zone) }
    }

    // Zurück-Taste/-Geste: Detail schließen, Auswahl aufheben oder Geräte-Einstellungen verlassen
    BackHandler(enabled = openId != null) { ridesVm.close() }
    BackHandler(enabled = openId == null && selection.isNotEmpty()) { ridesVm.clearSelection() }
    // Geräte-Tab mit verbundenem Gerät: Zurück trennt die Verbindung und führt zur Geräteliste
    BackHandler(
        enabled = tab == 2 && connectState.connectedName != null && openId == null && !showSettings && !showDeviceSettings && !showPages && !showRoutes && !showLive && selection.isEmpty(),
    ) { connectVm.disconnect() }
    BackHandler(enabled = showLive) { showLive = false }
    BackHandler(enabled = showRoutes) { showRoutes = false }
    BackHandler(enabled = showPages) { showPages = false }
    BackHandler(enabled = showDeviceSettings && !showPages && !showRoutes) { showDeviceSettings = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    // Verbindung weg: die Geräte-Einstellungen gibt es nicht mehr. Tabwechsel hebt eine Auswahl auf.
    LaunchedEffect(connectState.connectedName) { if (connectState.connectedName == null) { showDeviceSettings = false; showPages = false; showRoutes = false; showLive = false } }
    // Der Live-Bildschirm fragt das Gerät nur ab, solange er offen ist
    LaunchedEffect(showLive) {
        if (showLive) connectVm.startLive() else { connectVm.stopLive(); liveCustomize = false }
    }
    LaunchedEffect(tab) { ridesVm.clearSelection() }

    val settingsButton: @Composable () -> Unit = {
        IconButton(onClick = { showSettings = true }) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.nav_settings))
        }
    }

    // Meldungen des Geräte-Tabs (z. B. "Uhrzeit gesetzt") erscheinen kurz als Hinweis unten und nicht dauerhaft auf der Seite
    LaunchedEffect(connectState.message) {
        val msg = connectState.message ?: return@LaunchedEffect
        connectVm.clearMessage()
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(context.getString(msg.res, *msg.args.toTypedArray()), duration = androidx.compose.material3.SnackbarDuration.Long)
    }

    // Hinweis nach dem Archivieren/Zurückholen mit "Rückgängig"
    val archivedMsg = stringResource(R.string.archived_msg)
    val restoredMsg = stringResource(R.string.restored_msg)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(Unit) {
        ridesVm.archiveEvents.collect { ev ->
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                message = (if (ev.archived) archivedMsg else restoredMsg).format(ev.ids.size),
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) ridesVm.setArchived(ev.ids, !ev.archived, announce = false)
        }
    }

    // Schlüssel der aktuellen Leiste bzw. Seite; Wechsel werden animiert
    val barKey = when {
        openId != null -> 0
        showSettings -> 1
        showLive -> 8
        showRoutes -> 7
        showPages -> 5
        showDeviceSettings -> 2
        selection.isNotEmpty() -> 3
        tab == 2 && connectState.connectedName != null -> 6
        else -> 4
    }
    val screenKey = when {
        openId != null -> 0
        showSettings -> 1
        showLive -> 8
        showRoutes -> 7
        showPages -> 6
        showDeviceSettings -> 2
        tab == 0 -> 3
        tab == 1 -> 4
        else -> 5
    }
    // Zuletzt gezeigte Fahrt: bleibt während der Ausblend-Animation beim Schließen sichtbar
    val lastDetail = remember { arrayOfNulls<io.github.xpertsavenue.ridevault.model.StoredRide>(1) }
    detail?.let { lastDetail[0] = it }
    // Scrollstand und Lage des Titels der Detailseite: gehören hierher, weil die Titelleiste darauf reagiert.
    // Der Schlüssel ist die zuletzt geöffnete Fahrt, damit nichts springt, während die Seite ausgeblendet wird.
    val lastOpenId = remember { longArrayOf(-1L) }
    openId?.let { lastOpenId[0] = it }
    val detailScroll = remember(lastOpenId[0]) { androidx.compose.foundation.ScrollState(0) }
    var titleTop by remember(lastOpenId[0]) { androidx.compose.runtime.mutableFloatStateOf(Float.MAX_VALUE) }
    var titleHeight by remember(lastOpenId[0]) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Unterkante der Titelleiste im Fenster (Pixel); wird beim Aufbau des Inhalts gesetzt
    var barBottomPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // 0 = Titel unter der Karte noch sichtbar, 1 = ganz in die Titelleiste gewandert
    val titleProgress = {
        if (titleHeight <= 0f) {
            0f
        } else {
            // Lage der Titel-Oberkante im Fenster. Die Überblendung läuft, während der Titel die letzte Strecke bis zur Unterkante
            // der Leiste zurücklegt: 0 = noch eine Titelhöhe darunter, 1 = berührt die Leiste. Der Titel im Inhalt blendet mit
            // 1 - Fortschritt aus, der in der Leiste mit Fortschritt ein, beide mit demselben weichen Verlauf.
            val titleWindowTop = titleTop - detailScroll.value
            val linear = ((barBottomPx + titleHeight - titleWindowTop) / titleHeight).coerceIn(0f, 1f)
            linear * linear * (3f - 2f * linear) // weiche Kurve (smoothstep): kein abruptes Ein-/Ausblenden
        }
    }
    // Titelleiste der Detailseite: zuerst durchsichtig über der Karte, mit dem Scrollen immer deckender
    val density = androidx.compose.ui.platform.LocalDensity.current
    val mapHeightPx = with(density) { DetailMapHeight.toPx() }
    val barHasMap = (if (openId != null) detail else lastDetail[0])?.ride?.track?.let(::hasMapData) == true
    val barOpacity = { if (!barHasMap) 1f else (detailScroll.value / (0.8f * mapHeightPx)).coerceIn(0f, 1f) }
    val barSolid by remember(barHasMap, mapHeightPx) { androidx.compose.runtime.derivedStateOf { barOpacity() > 0.5f } }
    val barSurface = MaterialTheme.colorScheme.surface
    val barOnSurface = MaterialTheme.colorScheme.onSurface

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            androidx.compose.animation.AnimatedContent(
                targetState = barKey,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { -it / 3 }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                },
                label = "topBar",
            ) { key ->
            if (key == 0) {
                // Über der Karte: weiße Symbole auf der abgedunkelten Karte; danach normale Farben auf deckender Leiste
                val barContent = if (barSolid) barOnSurface else androidx.compose.ui.graphics.Color.White
                TopAppBar(
                    modifier = Modifier.drawBehind { drawRect(barSurface.copy(alpha = barOpacity())) },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        titleContentColor = barContent, navigationIconContentColor = barContent, actionIconContentColor = barContent,
                    ),
                    title = {
                        Text(
                            (if (openId != null) detail else lastDetail[0]).let { it?.name ?: it?.ride?.startTime?.format(dateTimeFormatter()) } ?: "",
                            maxLines = 1,
                            // Erscheint erst, wenn der Titel unter der Karte aus dem Bild läuft, und gleitet dabei von unten ein
                            modifier = Modifier.graphicsLayer {
                                val p = titleProgress()
                                alpha = p
                                translationY = (1f - p) * 10.dp.toPx()
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = ridesVm::close) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showShareImage = true }, enabled = detail != null) {
                            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share_image))
                        }
                        IconButton(onClick = { showEdit = true }, enabled = detail != null) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.nav_edit))
                        }
                    },
                )
            } else if (key == 1) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                )
            } else if (key == 8) {
                TopAppBar(
                    title = { Text(stringResource(R.string.live_title)) },
                    navigationIcon = {
                        IconButton(onClick = { showLive = false }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { liveCustomize = !liveCustomize }) {
                            Icon(painterResource(if (liveCustomize) R.drawable.ic_check_circle else R.drawable.ic_edit), stringResource(R.string.live_customize))
                        }
                    },
                )
            } else if (key == 7) {
                TopAppBar(
                    title = { Text(stringResource(R.string.routes_title)) },
                    navigationIcon = {
                        IconButton(onClick = { showRoutes = false }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { routesHelp = !routesHelp }) {
                            Icon(painterResource(R.drawable.ic_info), stringResource(R.string.pages_help_title))
                        }
                    },
                )
            } else if (key == 5) {
                TopAppBar(
                    title = { Text(stringResource(R.string.pages_title)) },
                    navigationIcon = {
                        IconButton(onClick = { showPages = false }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                    actions = {
                        // Erklärung nur auf Wunsch
                        IconButton(onClick = { pagesHelp = !pagesHelp }) {
                            Icon(painterResource(R.drawable.ic_info), stringResource(R.string.pages_help_title))
                        }
                    },
                )
            } else if (key == 2) {
                TopAppBar(
                    title = { Text(stringResource(R.string.dev_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = { showDeviceSettings = false }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.nav_back))
                        }
                    },
                )
            } else if (key == 3) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selection_count, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = ridesVm::clearSelection) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.sel_clear))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSelectionTags = true }) {
                            Icon(painterResource(R.drawable.ic_label), stringResource(R.string.sel_tags))
                        }
                        IconButton(onClick = { ridesVm.setArchived(selection, !showArchive) }) {
                            Icon(
                                painterResource(if (showArchive) R.drawable.ic_unarchive else R.drawable.ic_archive),
                                stringResource(if (showArchive) R.string.restore_from_archive else R.string.archive_action),
                            )
                        }
                        IconButton(onClick = { ridesVm.askDelete(selection) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.sel_delete))
                        }
                        Box {
                            IconButton(onClick = { exportMenu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.sel_more))
                            }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sel_show_map)) },
                                    onClick = { exportMenu = false; ridesVm.showSelectedOnMap() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sel_select_all)) },
                                    onClick = { exportMenu = false; ridesVm.selectAll(allRides.map { it.id }) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sel_export_zip)) },
                                    onClick = { exportMenu = false; selZipLauncher.launch("RideVault-" + java.time.LocalDate.now() + ".zip") },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.health_export)) },
                                    onClick = { exportMenu = false; runHealth(R.string.health_export_done) { health.export(ridesVm.loadSelected(), zone) } },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.health_remove)) },
                                    onClick = { exportMenu = false; runHealth(R.string.health_removed) { health.remove(ridesVm.loadSelected()) } },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sel_export_one)) },
                                    onClick = { exportMenu = false; selGpxLauncher.launch("RideVault-" + java.time.LocalDate.now() + ".gpx") },
                                )
                            }
                        }
                    },
                )
            } else if (key == 6) {
                // Verbundenes Gerät: Pfeil links trennt die Verbindung (wie die Zurück-Taste)
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = connectVm::disconnect) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.device_disconnect))
                        }
                    },
                    actions = { settingsButton() },
                )
            } else {
                CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = { settingsButton() })
            }
            }
        },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = openId == null && !showDeviceSettings && !showSettings,
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { it } + androidx.compose.animation.fadeOut(),
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(painterResource(R.drawable.ic_chart), null) },
                        label = { Text(stringResource(R.string.nav_stats)) },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(painterResource(R.drawable.ic_list), null) },
                        label = { Text(stringResource(R.string.nav_rides)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(painterResource(R.drawable.ic_bluetooth), null) },
                        label = { Text(stringResource(R.string.nav_device)) },
                    )
                }
            }
        },
    ) { padding ->
        // Die Titelleiste ist nur auf der Detailseite durchsichtig: dort reicht der Inhalt bis an den oberen Rand
        SideEffect { barBottomPx = with(density) { padding.calculateTopPadding().toPx() } }
        Box(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedContent(
                targetState = screenKey,
                transitionSpec = {
                    val deeper = { k: Int -> k <= 2 || k == 6 || k == 7 } // Detail, Einstellungen und Geräte-Einstellungen sind "tiefer"
                    when {
                        deeper(targetState) && !deeper(initialState) ->
                            (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 5 } +
                                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                        !deeper(targetState) && deeper(initialState) ->
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)) togetherWith
                                (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { it / 5 } +
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)))
                        else ->
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                    }
                },
                label = "screen",
            ) { sk ->
            Box(
                Modifier.fillMaxSize().padding(
                    if (sk == 0) PaddingValues(bottom = padding.calculateBottomPadding())
                    else PaddingValues(start = 16.dp, top = padding.calculateTopPadding(), end = 16.dp, bottom = padding.calculateBottomPadding()),
                ),
            ) {
            when (sk) {
                0 -> RideDetailScreen(
                    if (openId != null) detail else lastDetail[0], settings, store::update,
                    onExportGpx = { detail?.let { gpxLauncher.launch(Exporters.fileBaseName(it.ride, it.name) + ".gpx") } },
                    onExportTcx = { detail?.let { tcxLauncher.launch(Exporters.fileBaseName(it.ride, it.name) + ".tcx") } },
                    healthExported = detail?.let { health.key(it.ride) in healthExported } == true,
                    onHealth = {
                        detail?.let { d ->
                            if (health.key(d.ride) in healthExported) runHealth(R.string.health_removed) { health.remove(listOf(d)) }
                            else runHealth(R.string.health_export_done) { health.export(listOf(d), zone) }
                        }
                    },
                    onDelete = ridesVm::deleteSelected,
                    onArchive = { ridesVm.archiveOpened(detail?.archived != true) },
                    scroll = detailScroll,
                    titleProgress = titleProgress,
                    topInset = padding.calculateTopPadding(),
                    onTitleLayout = { top, h ->
                        // nur bei echter Änderung schreiben: die Lage wird beim Scrollen ständig neu gemeldet
                        if (kotlin.math.abs(top - titleTop) > 1f) titleTop = top
                        if (kotlin.math.abs(h - titleHeight) > 1f) titleHeight = h
                    },
                )
                8 -> LiveScreen(connectState.liveUi, liveCustomize, connectVm::setLiveFields)
                7 -> {
                    // Strecken: beim Öffnen die Liste vom Gerät lesen
                    LaunchedEffect(connectState.connectedName) { connectVm.loadRoutes() }
                    RoutesScreen(
                        state = connectState.routeUi,
                        busy = connectState.busy,
                        onPickFile = { routeFilePicker.launch(arrayOf("*/*")) },
                        onDelete = connectVm::deleteRoute,
                        onUse = connectVm::useRoute,
                        onRename = connectVm::renameRoute,
                        onReload = connectVm::loadRoutes,
                        helpOpen = routesHelp,
                    )
                }
                6 -> {
                    // Seiten einrichten: beim Öffnen vom Gerät lesen
                    LaunchedEffect(connectState.connectedName) { connectVm.openPages() }
                    PagesScreen(
                        state = connectState.pageUi,
                        busy = connectState.busy,
                        onMode = connectVm::selectPageMode,
                        onEdit = connectVm::editPage,
                        onApply = connectVm::applyPages,
                        onDiscard = connectVm::discardPageChanges,
                        onRestore = connectVm::restorePagesBackup,
                        helpOpen = pagesHelp,
                    )
                }
                1 -> SettingsScreen(
                    settings = settings,
                    onChange = { new ->
                        val languageChanged = new.language != settings.language
                        store.update(new)
                        if (languageChanged) activity?.recreate() // Texte in der neuen Sprache neu aufbauen
                    },
                    onManageTags = { showSettings = false; showTags = true },
                    onExportAll = { zipLauncher.launch("RideVault-" + java.time.LocalDate.now() + ".zip") },
                    onBackup = { backupLauncher.launch("RideVault-backup-" + java.time.LocalDate.now() + ".db") },
                    onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                )
                2 -> Column(
                    Modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (connectState.configItems.isNotEmpty()) {
                        // Geräte, die ihre Einstellungen melden (iGPSPORT): beim Öffnen vom Gerät lesen
                        LaunchedEffect(connectState.connectedName) { connectVm.loadConfig() }
                        if (connectState.pagesSupported) {
                            androidx.compose.material3.Button(
                                onClick = { showPages = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.pages_open)) }
                        }
                        ConfigSettingsCard(
                            items = connectState.configItems,
                            state = connectState.config,
                            loading = connectState.configLoading,
                            busy = connectState.busy,
                            onChange = connectVm::setConfig,
                            onReload = connectVm::loadConfig,
                        )
                    } else {
                        DeviceSettingsCard(
                            values = connectState.deviceValues,
                            busy = connectState.busy,
                            autoSyncTime = settings.autoSyncTime,
                            onAutoSyncTime = { store.update(settings.copy(autoSyncTime = it)) },
                            onApply = connectVm::applyDeviceSetting,
                        )
                    }
                }
                3 -> StatsScreen(ridesVm, onOpenRide = { tab = 1; ridesVm.open(it) })
                4 -> RidesScreen(ridesVm, settings, store::update)
                else -> DeviceScreen(connectVm, onOpenDeviceSettings = { showDeviceSettings = true }, onOpenRoutes = { showRoutes = true }, onOpenLive = { showLive = true })
            }
            }
            }
        }
    }
    mapRides?.let { MultiRideMapDialog(it, settings, store::update, onClose = ridesVm::closeMap) }
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(R.string.restore_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    val uri = restoreUri ?: return@TextButton
                    scope.launch {
                        val ok = runCatching { DataTransfer.restore(app, uri) }.getOrDefault(false)
                        if (ok) {
                            // Datenbank wurde ausgetauscht: App sauber neu starten
                            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            context.startActivity(launch)
                            Runtime.getRuntime().exit(0)
                        } else {
                            Toast.makeText(context, context.getString(R.string.restore_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showShareImage) {
        detail?.let { d ->
            // Nur anbieten, was die Fahrt hat: Kennzahlen/Diagramme ohne Daten fallen weg
            LaunchedEffect(d.id) {
                shareOptions = shareOptions.copy(
                    stats = shareOptions.stats.filter { it in availableStats(d.ride) }.toSet()
                        .ifEmpty { setOf(ShareStat.DISTANCE, ShareStat.DURATION, ShareStat.AVG_SPEED).filter { it in availableStats(d.ride) }.toSet() },
                    charts = shareOptions.charts.filter { it in availableCharts(d.ride) }.toSet(),
                )
            }
            ShareImageDialog(
                stored = d,
                options = shareOptions,
                onOptions = { shareOptions = it },
                onShare = { opts ->
                    val uri = createShareImage(context, d, opts, shareAccent, sharePalette)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_image)))
                },
                onDismiss = { showShareImage = false },
            )
        }
    }
    if (showSelectionTags) {
        SelectionTagsDialog(
            rides = allRides.filter { it.id in selection },
            allTags = allTags,
            createTag = ridesVm::createTag,
            onApply = { add, remove -> ridesVm.changeTagsOfSelection(add, remove); showSelectionTags = false },
            onDismiss = { showSelectionTags = false },
        )
    }
    pendingDelete?.let { ids ->
        DeleteDialog(
            count = ids.size, canArchive = !showArchive,
            onDelete = ridesVm::confirmDelete,
            onArchive = { ridesVm.cancelDelete(); ridesVm.setArchived(ids, true) },
            onDismiss = ridesVm::cancelDelete,
        )
    }

    if (showEdit) {
        detail?.let { d ->
            EditRideDialog(
                stored = d,
                allTags = allTags,
                createTag = ridesVm::createTag,
                onSave = { name, tagIds -> ridesVm.saveRideInfo(d.id, name, tagIds); showEdit = false },
                onDismiss = { showEdit = false },
            )
        }
    }
    if (showTags) {
        ManageTagsDialog(
            tags = allTags,
            onUpdate = ridesVm::updateTag,
            onDelete = ridesVm::deleteTag,
            onCreate = { name, color -> ridesVm.createTag(name, color) },
            onDismiss = { showTags = false },
        )
    }
}

/** Ein Eintrag der Geräteliste: gemerkt, in der Nähe gefunden oder beides. */
private data class DeviceEntry(val address: String, val name: String, val known: KnownDevice?, val found: FoundDevice?)

@Composable
private fun DeviceScreen(vm: ConnectViewModel, onOpenDeviceSettings: () -> Unit, onOpenRoutes: () -> Unit, onOpenLive: () -> Unit) {
    val state by vm.state.collectAsState()
    // Ein gemerktes Gerät wartet auf die Bluetooth-Berechtigung; sonst startet die Berechtigung die Suche
    var pendingKnown by remember { mutableStateOf<KnownDevice?>(null) }
    var forgetCandidate by remember { mutableStateOf<KnownDevice?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val known = pendingKnown
        pendingKnown = null
        if (granted.values.all { it }) { if (known != null) vm.connectKnown(known) else vm.startScan() }
    }

    val connected = state.connectedName != null
    // Verbunden: ganze Spalte scrollbar (Karten). Nicht verbunden: die Trefferliste scrollt selbst.
    Column(
        Modifier.fillMaxSize().padding(top = 8.dp).then(if (connected) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (connected) {
            ConnectedCard(
                state, onSyncRides = vm::syncRides, onSyncTime = vm::syncTime, onDisconnect = vm::disconnect,
                // Geräte-Einstellungen nur, wenn das Gerät sie unterstützt (z. B. BIKEVEE, noch nicht iGPSPORT)
                onOpenSettings = if (Capability.SET_UNITS in state.capabilities || state.configItems.isNotEmpty()) onOpenDeviceSettings else null,
                // Strecken zur Navigation laden: nur Geräte, die das können (iGPSPORT)
                onOpenRoutes = if (state.routesSupported) onOpenRoutes else null,
                onOpenLive = if (state.liveSupported) onOpenLive else null,
            )
        } else {
            Button(
                onClick = { permissions.launch(BlePermissions.required()) },
                enabled = !state.scanning && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (state.scanning) R.string.device_searching else R.string.device_search)) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.device_show_all), Modifier.weight(1f))
                Switch(checked = state.showAll, onCheckedChange = vm::setShowAll)
            }
            if (state.scanning || state.busy) CircularProgressIndicator()

            // Eine Liste: gemerkte Geräte (zuerst, zuletzt benutzte oben) und in der Nähe gefundene; dasselbe Gerät erscheint nur einmal
            val entries = remember(state.knownDevices, state.found, state.showAll) {
                val map = LinkedHashMap<String, DeviceEntry>()
                state.knownDevices.forEach { map[it.address] = DeviceEntry(it.address, it.name, it, null) }
                state.found.filter { state.showAll || it.model != null || it.scan.address in map }.forEach { f ->
                    val old = map[f.scan.address]
                    map[f.scan.address] = old?.copy(found = f) ?: DeviceEntry(f.scan.address, f.scan.name ?: f.scan.address, null, f)
                }
                map.values.toList()
            }
            if (entries.isNotEmpty()) Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.address }) { e ->
                    val nearbyNow = e.found != null
                    Card(
                        colors = if (nearbyNow) {
                            androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                        } else androidx.compose.material3.CardDefaults.cardColors(),
                        border = if (nearbyNow) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        onClick = {
                            val found = e.found
                            val known = e.known
                            if (found != null) vm.connect(found) else if (known != null) { pendingKnown = known; permissions.launch(BlePermissions.required()) }
                        },
                        enabled = !state.busy,
                    ) {
                        ListItem(
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = { Text(e.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = {
                                val model = e.known?.let { "${it.vendor} ${it.model}".trim() }
                                    ?: e.found?.model?.let { "${it.vendor} ${it.name}" }
                                    ?: stringResource(R.string.device_unrecognized)
                                val last = e.known?.let {
                                    stringResource(R.string.known_last, java.time.Instant.ofEpochMilli(it.lastConnected).formatLocal(dateTimeFormatter()))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(listOfNotNull(model, last).joinToString(" · "))
                                    if (nearbyNow) {
                                        // Deutliches Kennzeichen: gefüllte Marke mit Bluetooth-Symbol in der Akzentfarbe
                                        androidx.compose.material3.Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                        ) {
                                            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(painterResource(R.drawable.ic_bluetooth), null, Modifier.size(16.dp))
                                                Text(
                                                    stringResource(R.string.device_nearby),
                                                    Modifier.padding(start = 6.dp),
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            trailingContent = e.known?.let { k ->
                                {
                                    IconButton(onClick = { forgetCandidate = k }, enabled = !state.busy) {
                                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.known_forget))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    forgetCandidate?.let { k ->
        AlertDialog(
            onDismissRequest = { forgetCandidate = null },
            title = { Text(stringResource(R.string.known_forget_title, k.name)) },
            text = { Text(stringResource(R.string.known_forget_text)) },
            confirmButton = { TextButton(onClick = { vm.forgetDevice(k.address); forgetCandidate = null }) { Text(stringResource(R.string.known_forget)) } },
            dismissButton = { TextButton(onClick = { forgetCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ConnectedCard(
    state: ConnectUiState,
    onSyncRides: () -> Unit,
    onSyncTime: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onOpenRoutes: (() -> Unit)? = null,
    onOpenLive: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.connectedName.orEmpty(), style = MaterialTheme.typography.titleLarge)
            // Nur anzeigen, was das Gerät liefert (der BSC200 z. B. meldet keinen Akkustand über diesen Weg)
            state.info?.let { info ->
                info.batteryPercent?.let { Text(stringResource(R.string.device_battery, it.toString())) }
                info.firmware?.let { fw ->
                    Text(if (info.firmwareDate != null) stringResource(R.string.device_firmware, fw, info.firmwareDate!!) else "Firmware: $fw")
                }
                info.hardware?.let { Text(stringResource(R.string.device_hardware, it)) }
                info.manufacturer?.let { Text(stringResource(R.string.device_manufacturer, it)) }
                info.serial?.let { Text(stringResource(R.string.device_serial, it)) }
            }
            state.health?.let { h ->
                h.batteryPercent?.let { pct ->
                    Text(stringResource(R.string.device_battery, pct.toString()))
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                h.storageFreePercent?.let { pct ->
                    Text(stringResource(R.string.dash_storage, pct.toString()))
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                val ride = when (h.rideState) {
                    io.github.xpertsavenue.ridevault.device.RideState.RIDING -> stringResource(R.string.live_state_riding)
                    io.github.xpertsavenue.ridevault.device.RideState.PAUSED -> stringResource(R.string.live_state_paused)
                    io.github.xpertsavenue.ridevault.device.RideState.IDLE -> stringResource(R.string.live_state_idle)
                    null -> null
                }
                val navi = h.navigating?.let { if (it) stringResource(R.string.dash_navi_on) else stringResource(R.string.dash_navi_off) }
                listOfNotNull(ride, navi).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · ")) }
            }
            state.syncLabel?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
            if (state.syncLabel != null) {
                val p = state.syncProgress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // Fahrtenliste wird gelesen: Dauer unbekannt
            }
            Button(onClick = onSyncRides, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.device_import_rides))
            }
            onOpenRoutes?.let {
                OutlinedButton(onClick = it, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.routes_open))
                }
            }
            onOpenLive?.let {
                OutlinedButton(onClick = it, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.live_open))
                }
            }
            onOpenSettings?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_settings_title)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Capability.SET_TIME in state.capabilities) {
                    OutlinedButton(onClick = onSyncTime, enabled = !state.busy) { Text(stringResource(R.string.device_set_time)) }
                }
                OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.device_disconnect)) }
            }
        }
    }
}
