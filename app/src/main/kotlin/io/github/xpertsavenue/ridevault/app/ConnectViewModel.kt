package io.github.xpertsavenue.ridevault.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xpertsavenue.ridevault.ble.BleGattConnection
import io.github.xpertsavenue.ridevault.ble.BleScanner
import io.github.xpertsavenue.ridevault.device.BikeComputerSession
import io.github.xpertsavenue.ridevault.driver.igpsport.IgpsportDriver
import io.github.xpertsavenue.ridevault.device.Capability
import io.github.xpertsavenue.ridevault.device.ConfigItem
import io.github.xpertsavenue.ridevault.device.ConfigState
import io.github.xpertsavenue.ridevault.device.ConfigurableSession
import io.github.xpertsavenue.ridevault.device.DevicePage
import io.github.xpertsavenue.ridevault.device.DeviceRoute
import io.github.xpertsavenue.ridevault.device.DeviceHealth
import io.github.xpertsavenue.ridevault.device.LiveSession
import io.github.xpertsavenue.ridevault.device.PageCapabilities
import io.github.xpertsavenue.ridevault.device.RouteConfigurableSession
import io.github.xpertsavenue.ridevault.device.PageConfigurableSession
import io.github.xpertsavenue.ridevault.device.RidingMode
import io.github.xpertsavenue.ridevault.device.BikeComputerDriver
import io.github.xpertsavenue.ridevault.device.DeviceInfo
import io.github.xpertsavenue.ridevault.device.DeviceModel
import io.github.xpertsavenue.ridevault.device.DeviceRef
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.ScanInfo
import io.github.xpertsavenue.ridevault.device.SyncEvent
import io.github.xpertsavenue.ridevault.driver.bikevee.BikeveeDriver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset

data class FoundDevice(val scan: ScanInfo, val model: DeviceModel?, val driver: BikeComputerDriver? = null)

/** Stand der Seiten-Einrichtung (Datenfelder und Anordnung) eines Geräts. */
data class PageUiState(
    val modes: List<RidingMode> = emptyList(),
    val mode: Int? = null,
    val pages: List<DevicePage> = emptyList(),
    /** Zuletzt vom Gerät gelesener Stand; Änderungen sind Abweichungen davon. */
    val original: List<DevicePage> = emptyList(),
    val capabilities: PageCapabilities? = null,
    val loading: Boolean = false,
    val hasBackup: Boolean = false,
) {
    val dirty: Boolean get() = pages != original
}

data class ConnectUiState(
    val scanning: Boolean = false,
    val showAll: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    /** Geräte, mit denen die App schon verbunden war. */
    val knownDevices: List<KnownDevice> = emptyList(),
    val busy: Boolean = false,
    val connectedName: String? = null,
    val info: DeviceInfo? = null,
    val message: UiMsg? = null,
    /** 0..1 während der Fahrten-Synchronisation, sonst null. */
    val syncProgress: Float? = null,
    /** Was gerade geladen wird (z. B. "Fahrt 2 von 5 · 07.11.2026 14:15 · 40 %"); null außerhalb der Synchronisation. */
    val syncLabel: UiMsg? = null,
    /** Zuletzt an das verbundene Gerät gesendete Einstellungen (das Gerät meldet sie nicht zurück). */
    val deviceValues: DeviceValues = DeviceValues(),
    /** Was das verbundene Gerät kann (bestimmt, welche Bedienelemente erscheinen). */
    val capabilities: Set<Capability> = emptySet(),
    /** Einstellungen, die das verbundene Gerät lesen/ändern lässt (leer, wenn es das nicht kann). */
    val configItems: List<ConfigItem> = emptyList(),
    val config: ConfigState? = null,
    val configLoading: Boolean = false,
    /** Das Gerät lässt seine Seiten (Datenfelder, Anordnung) einrichten. */
    val pagesSupported: Boolean = false,
    val pageUi: PageUiState = PageUiState(),
    /** Das Gerät nimmt Strecken zur Navigation an. */
    val routesSupported: Boolean = false,
    val routeUi: RouteUiState = RouteUiState(),
    /** Das Gerät meldet Livewerte und seinen Zustand. */
    val liveSupported: Boolean = false,
    val liveUi: LiveUiState = LiveUiState(),
    /** Akku, Speicher, Fahr- und Navigationsstatus (null, solange nicht gelesen). */
    val health: DeviceHealth? = null,
)

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    /** Alle bekannten Treiber; beim Suchen entscheidet der erste, der den Gerätenamen erkennt. */
    private val drivers: List<BikeComputerDriver> = listOf(BikeveeDriver(), IgpsportDriver())
    private val scanner = BleScanner(app)
    private val repo = (app as RideVaultApp).rides
    private val appSettings = (app as RideVaultApp).settings
    private val deviceStore = DeviceSettingsStore(app)
    private val knownStore = KnownDevicesStore(app)
    private val pageBackups = PageBackupStore(app)
    private var address: String? = null

    private val _state = MutableStateFlow(ConnectUiState(knownDevices = knownStore.load()))
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var connection: BleGattConnection? = null
    private var session: BikeComputerSession? = null

    fun setShowAll(value: Boolean) = _state.update { it.copy(showAll = value) }

    fun startScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, found = emptyList(), message = null) }
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_MS) {
                scanner.scan()
                    .catch { e -> _state.update { it.copy(message = UiMsg(R.string.msg_error, listOf(e.message ?: ""))) } }
                    .collect { scan ->
                        val match = drivers.firstNotNullOfOrNull { d -> d.identify(scan)?.let { m -> d to m } }
                        val model = match?.second
                        // Ohne Namen ist ein Treffer nie nützlich
                        if (scan.name == null) return@collect
                        _state.update { s ->
                            val others = s.found.filterNot { it.scan.address == scan.address }
                            s.copy(found = (others + FoundDevice(scan, model, match?.first)).sortedByDescending { it.model != null })
                        }
                    }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun connect(device: FoundDevice) {
        stopScan()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                // Nicht erkannte Geräte ("Alle anzeigen") werden wie bisher mit dem BIKEVEE-Treiber versucht
                val driver = device.driver ?: drivers.first()
                val conn = BleGattConnection.connect(getApplication(), device.scan.address, driver.gattProfile)
                connection = conn
                val ref = DeviceRef(device.scan.address, device.scan.name ?: "?", device.model ?: DeviceModel("BIKEVEE", "unbekannt"))
                val s = driver.open(ref, conn).also { session = it }
                val info = s.deviceInfo()
                address = ref.address
                // Gerät merken, damit es beim nächsten Mal ohne Suche wieder verbunden werden kann
                val known = knownStore.remember(
                    KnownDevice(ref.address, ref.name, driver.id, ref.model.vendor, ref.model.name, System.currentTimeMillis()),
                )
                _state.update { it.copy(knownDevices = known) }
                // Optional: Uhrzeit gleich beim Verbinden stellen. Ein Fehler hier bricht die Verbindung nicht ab.
                if (appSettings.state.value.autoSyncTime && Capability.SET_TIME in s.capabilities) {
                    runCatching { s.apply(DeviceSetting.Time(LocalDateTime.now(), LocalDateTime.now(ZoneOffset.UTC), 0)) }
                }
                _state.update {
                    it.copy(connectedName = ref.name, info = info, busy = false, deviceValues = deviceStore.load(ref.address), capabilities = s.capabilities,
                        configItems = (s as? ConfigurableSession)?.configItems.orEmpty(), config = null,
                        pagesSupported = s is PageConfigurableSession, pageUi = PageUiState(),
                        routesSupported = s is RouteConfigurableSession, routeUi = RouteUiState(),
                        liveSupported = s is LiveSession, liveUi = LiveUiState(fields = loadLiveFields()), health = null,
                    )
                }
                loadHealth()
            } catch (e: Exception) {
                disconnect()
                _state.update { it.copy(busy = false, message = UiMsg(R.string.msg_connect_failed, listOf(e.message ?: ""))) }
            }
        }
    }

    /** Entfernt die angezeigte Meldung (nachdem sie als Hinweis erschienen ist). */
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Verbindet ein gemerktes Gerät direkt (ohne Suche). */
    fun connectKnown(device: KnownDevice) {
        val driver = drivers.firstOrNull { it.id == device.driverId } ?: return
        connect(FoundDevice(ScanInfo(device.name, device.address), DeviceModel(device.vendor, device.model), driver))
    }

    /** Entfernt ein Gerät aus der Liste der bekannten Geräte. Die Bluetooth-Kopplung von Android bleibt davon unberührt. */
    fun forgetDevice(address: String) {
        _state.update { it.copy(knownDevices = knownStore.forget(address)) }
    }

    fun syncTime() = runOnDevice(UiMsg(R.string.msg_time_set)) {
        apply(DeviceSetting.Time(LocalDateTime.now(), LocalDateTime.now(ZoneOffset.UTC), 0))
    }

    /** Sendet eine Einstellung an das Gerät und merkt sich den Wert nur, wenn das Gerät sie bestätigt hat. */
    fun applyDeviceSetting(setting: DeviceSetting) {
        val s = session ?: return
        val addr = address
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.apply(setting)
                val updated = _state.value.deviceValues.with(setting)
                if (addr != null) deviceStore.save(addr, updated)
                _state.update { it.copy(deviceValues = updated) }
                UiMsg(R.string.msg_setting_saved)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    /** Holt neue Fahrten. Jede Fahrt wird erst gespeichert, dann (vom Treiber) dem Gerät bestätigt. */
    fun syncRides() {
        val s = session ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null, syncProgress = null, syncLabel = UiMsg(R.string.sync_reading_list)) }
            var saved = 0
            var skipped = 0
            // Gesamtfortschritt über alle zu ladenden Fahrten: fertige Fahrten + Anteil der laufenden
            var index = 0
            var total = 0
            var start: LocalDateTime? = null
            var fraction = 0f
            fun publish() {
                val whole = if (total > 0) ((index - 1).coerceAtLeast(0) + fraction) / total else fraction
                val label = if (start != null) {
                    UiMsg(R.string.sync_loading, listOf(index, total, start!!.format(dateTimeFormatter()), (fraction * 100).toInt()))
                } else {
                    UiMsg(R.string.sync_loading_one, listOf((fraction * 100).toInt()))
                }
                _state.update { it.copy(syncProgress = whole.coerceIn(0f, 1f), syncLabel = label) }
            }
            val msg = try {
                s.syncRides { repo.exists(it) }.collect { ev ->
                    when (ev) {
                        is SyncEvent.Loading -> { index = ev.index; total = ev.total; start = ev.startTime; fraction = 0f; publish() }
                        is SyncEvent.Progress -> if (ev.total > 0) {
                            if (index == 0) { index = 1; total = 1 } // Gerät ohne Fahrtenliste (z. B. BIKEVEE): eine Fahrt
                            fraction = (ev.received.toFloat() / ev.total).coerceIn(0f, 1f)
                            publish()
                        }
                        is SyncEvent.RideReceived -> { repo.save(ev.ride, _state.value.connectedName); saved++ }
                        is SyncEvent.RideSkipped -> skipped++
                        else -> Unit
                    }
                }
                when {
                    saved > 0 -> UiMsg(R.string.msg_sync_saved, listOf(saved))
                    skipped > 0 -> UiMsg(R.string.msg_sync_none_new)
                    else -> UiMsg(R.string.msg_sync_empty)
                }
            } catch (e: Exception) {
                if (saved > 0) UiMsg(R.string.msg_sync_aborted_saved, listOf(e.message ?: "", saved))
                else UiMsg(R.string.msg_sync_aborted, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg, syncProgress = null, syncLabel = null) }
        }
    }

    private fun runOnDevice(done: UiMsg, block: suspend BikeComputerSession.() -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.block()
                done
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    /** Liest die Einstellungen vom Gerät (für die Seite "Geräte-Einstellungen"). */
    fun loadConfig() {
        val s = session as? ConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(configLoading = true, busy = true, message = null) }
            val result = try {
                s.readConfig()
            } catch (e: Exception) {
                _state.update { it.copy(message = UiMsg(R.string.msg_error, listOf(e.message ?: ""))) }
                null
            }
            _state.update { it.copy(config = result, configLoading = false, busy = false) }
        }
    }

    // ---- Strecken (Navigation) --------------------------------------------------------------------------------------------

    /** Liest die Streckenliste des Geräts. */
    fun loadRoutes() {
        val s = session as? RouteConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(routeUi = it.routeUi.copy(loading = true), busy = true, message = null) }
            try {
                val routes = s.readRoutes()
                _state.update { it.copy(routeUi = it.routeUi.copy(routes = routes, loading = false, loaded = true)) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(routeUi = it.routeUi.copy(loading = false, loaded = true), message = UiMsg(R.string.msg_error, listOf(e.message ?: "")))
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Sendet eine Streckendatei (GPX, FIT, TCX, CNX) vom Handy an das Gerät und liest danach die Liste neu. */
    fun sendRoute(uri: android.net.Uri) {
        val s = session as? RouteConfigurableSession ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(routeUi = it.routeUi.copy(sending = 0), busy = true, message = null) }
            val msg = try {
                val (fileName, bytes) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val resolver = app.contentResolver
                    val display = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "route.gpx"
                    display to (resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Datei nicht lesbar"))
                }
                val ext = fileName.substringAfterLast('.', "gpx").lowercase()
                // Name für das Gerät: ohne Endung, einfache Zeichen, kurz
                val name = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]+"), "_").trim().take(24).ifEmpty { "Route" }
                s.sendRoute(name, ext, bytes) { p -> _state.update { it.copy(routeUi = it.routeUi.copy(sending = p)) } }
                UiMsg(R.string.routes_sent)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(routeUi = it.routeUi.copy(sending = null), message = msg) }
            try {
                val routes = s.readRoutes()
                _state.update { it.copy(routeUi = it.routeUi.copy(routes = routes, loaded = true)) }
            } catch (_: Exception) {
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun deleteRoute(route: DeviceRoute) {
        val s = session as? RouteConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.deleteRoute(route)
                val routes = s.readRoutes()
                _state.update { it.copy(routeUi = it.routeUi.copy(routes = routes)) }
                // Hat das Gerät die Strecke trotz Bestätigung behalten, ist sie weiter in der Liste
                if (routes.any { it.id == route.id }) UiMsg(R.string.routes_not_deleted) else UiMsg(R.string.routes_deleted)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    fun useRoute(route: DeviceRoute) {
        val s = session as? RouteConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.useRoute(route)
                val routes = s.readRoutes()
                _state.update { it.copy(routeUi = it.routeUi.copy(routes = routes)) }
                // Meldet das Gerät die Strecke danach nicht als benutzt, hat es den Wechsel nicht übernommen
                if (routes.firstOrNull { it.id == route.id }?.inUse == false) UiMsg(R.string.routes_not_used) else UiMsg(R.string.routes_used)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    // ---- Livewerte und Gerätezustand ----------------------------------------------------------------------------------------

    private var liveJob: Job? = null
    private val livePrefs get() = getApplication<Application>().getSharedPreferences("live", 0)

    private fun loadLiveFields(): List<String> {
        val saved = runCatching { livePrefs.getString("fields", null) }.getOrNull()
        val ids = saved?.split(',')?.filter { id -> LiveField.entries.any { it.id == id } }
        return if (ids.isNullOrEmpty()) LiveField.defaults.map { it.id } else ids
    }

    /** Welche Werte der Live-Bildschirm zeigt (Reihenfolge = Reihenfolge der Kacheln). */
    fun setLiveFields(ids: List<String>) {
        _state.update { it.copy(liveUi = it.liveUi.copy(fields = ids)) }
        runCatching { livePrefs.edit().putString("fields", ids.joinToString(",")).apply() }
    }

    /** Liest Akku, Speicher und Status des Geräts; Fehler bleiben still (die Anzeige fehlt dann einfach). */
    fun loadHealth() {
        val s = session as? LiveSession ?: return
        viewModelScope.launch {
            val h = runCatching { s.readHealth() }.getOrNull()
            if (h != null) _state.update { it.copy(health = h) }
        }
    }

    /** Fragt das Gerät sekündlich ab, solange der Live-Bildschirm offen ist. */
    fun startLive() {
        val s = session as? LiveSession ?: return
        if (liveJob?.isActive == true) return
        _state.update { it.copy(liveUi = it.liveUi.copy(failed = false, data = null)) }
        liveJob = viewModelScope.launch {
            var fails = 0
            while (true) {
                val d = runCatching { s.readLive() }.getOrNull()
                if (d == null) {
                    fails++
                    if (fails >= 3) {
                        _state.update { it.copy(liveUi = it.liveUi.copy(failed = true)) }
                        break
                    }
                } else {
                    fails = 0
                    _state.update { it.copy(liveUi = it.liveUi.copy(data = d, failed = false)) }
                }
                delay(1000)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    fun renameRoute(route: DeviceRoute, newName: String) {
        val s = session as? RouteConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.renameRoute(route, newName)
                val routes = s.readRoutes()
                _state.update { it.copy(routeUi = it.routeUi.copy(routes = routes)) }
                if (routes.any { it.id == route.id && it.name == newName }) UiMsg(R.string.routes_renamed) else UiMsg(R.string.routes_not_renamed)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    // ---- Seiten einrichten ------------------------------------------------------------------------------------------------

    /** Lädt Fahrmodi und die Seiten des aktuell benutzten Fahrmodus. */
    fun openPages() {
        val s = session as? PageConfigurableSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(pageUi = it.pageUi.copy(loading = true), busy = true, message = null) }
            try {
                val modes = s.readRidingModes()
                val mode = (modes.firstOrNull { it.inUse } ?: modes.firstOrNull())?.index
                loadPagesFor(s, modes, mode)
            } catch (e: Exception) {
                _state.update { it.copy(pageUi = it.pageUi.copy(loading = false), message = UiMsg(R.string.msg_error, listOf(e.message ?: ""))) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun loadPagesFor(s: PageConfigurableSession, modes: List<RidingMode>, mode: Int?) {
        val result = s.readPages(mode)
        val has = address?.let { pageBackups.has(it, mode) } ?: false
        _state.update {
            it.copy(pageUi = PageUiState(modes, mode, result.pages, result.pages, result.capabilities, loading = false, hasBackup = has))
        }
    }

    /** Wechselt den Fahrmodus, dessen Seiten bearbeitet werden. Nicht übernommene Änderungen gehen dabei verloren. */
    fun selectPageMode(mode: Int) {
        val s = session as? PageConfigurableSession ?: return
        val modes = _state.value.pageUi.modes
        viewModelScope.launch {
            _state.update { it.copy(pageUi = it.pageUi.copy(loading = true), busy = true, message = null) }
            try {
                loadPagesFor(s, modes, mode)
            } catch (e: Exception) {
                _state.update { it.copy(pageUi = it.pageUi.copy(loading = false), message = UiMsg(R.string.msg_error, listOf(e.message ?: ""))) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Übernimmt die Änderung an einer Seite lokal; zum Gerät geht sie erst mit [applyPages]. */
    fun editPage(page: DevicePage) {
        _state.update { st ->
            st.copy(pageUi = st.pageUi.copy(pages = st.pageUi.pages.map { if (it.index == page.index) page else it }))
        }
    }

    fun discardPageChanges() {
        _state.update { it.copy(pageUi = it.pageUi.copy(pages = it.pageUi.original)) }
    }

    /** Schreibt die Seiten zum Gerät. Vor der ersten Änderung wird der alte Stand gesichert. */
    fun applyPages() {
        val s = session as? PageConfigurableSession ?: return
        val ui = _state.value.pageUi
        val addr = address ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                pageBackups.saveIfMissing(addr, ui.mode, ui.original)
                s.writePages(ui.mode, ui.pages)
                loadPagesFor(s, ui.modes, ui.mode) // zurücklesen: gezeigt wird, was das Gerät wirklich gespeichert hat
                UiMsg(R.string.msg_setting_saved)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    /** Stellt den Stand wieder her, den die Seiten vor der ersten Änderung durch die App hatten. */
    fun restorePagesBackup() {
        val s = session as? PageConfigurableSession ?: return
        val ui = _state.value.pageUi
        val addr = address ?: return
        val backup = pageBackups.load(addr, ui.mode) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val msg = try {
                s.writePages(ui.mode, backup)
                loadPagesFor(s, ui.modes, ui.mode)
                UiMsg(R.string.msg_setting_saved)
            } catch (e: Exception) {
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    /** Sendet eine geänderte Einstellung sofort an das Gerät; schlägt das fehl, bleibt der alte Wert stehen. */
    fun setConfig(id: String, value: Int) {
        val s = session as? ConfigurableSession ?: return
        val before = _state.value.config ?: return
        viewModelScope.launch {
            _state.update { it.copy(config = before.copy(values = before.values + (id to value)), busy = true, message = null) }
            val msg = try {
                s.writeConfig(id, value)
                UiMsg(R.string.msg_setting_saved)
            } catch (e: Exception) {
                _state.update { it.copy(config = before) }
                UiMsg(R.string.msg_error, listOf(e.message ?: ""))
            }
            _state.update { it.copy(busy = false, message = msg) }
        }
    }

    fun disconnect() {
        stopLive()
        connection?.close()
        connection = null
        session = null
        address = null
        _state.update { it.copy(connectedName = null, info = null, deviceValues = DeviceValues(), capabilities = emptySet(), configItems = emptyList(), config = null, pagesSupported = false, pageUi = PageUiState(), routesSupported = false, routeUi = RouteUiState(), liveSupported = false, liveUi = LiveUiState(), health = null) }
    }

    override fun onCleared() {
        connection?.close()
    }

    private companion object {
        const val SCAN_MS = 12_000L
    }
}
