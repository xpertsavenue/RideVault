package io.github.xpertsavenue.ridevault.driver.igpsport

import io.github.xpertsavenue.ridevault.device.BikeComputerDriver
import io.github.xpertsavenue.ridevault.device.BikeComputerSession
import io.github.xpertsavenue.ridevault.device.Capability
import io.github.xpertsavenue.ridevault.device.ConfigItem
import io.github.xpertsavenue.ridevault.device.ConfigKind
import io.github.xpertsavenue.ridevault.device.ConfigState
import io.github.xpertsavenue.ridevault.device.ConfigurableSession
import io.github.xpertsavenue.ridevault.device.DevicePage
import io.github.xpertsavenue.ridevault.device.DeviceRoute
import io.github.xpertsavenue.ridevault.device.DeviceHealth
import io.github.xpertsavenue.ridevault.device.LiveData
import io.github.xpertsavenue.ridevault.device.LiveSession
import io.github.xpertsavenue.ridevault.device.RideState
import io.github.xpertsavenue.ridevault.device.RouteConfigurableSession
import io.github.xpertsavenue.ridevault.device.DevicePages
import io.github.xpertsavenue.ridevault.device.PageCapabilities
import io.github.xpertsavenue.ridevault.device.PageConfigurableSession
import io.github.xpertsavenue.ridevault.device.RidingMode
import io.github.xpertsavenue.ridevault.device.DeviceInfo
import io.github.xpertsavenue.ridevault.device.DeviceModel
import io.github.xpertsavenue.ridevault.device.DeviceRef
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.GattChannel
import io.github.xpertsavenue.ridevault.device.GattProfile
import io.github.xpertsavenue.ridevault.device.ScanInfo
import io.github.xpertsavenue.ridevault.device.SyncEvent
import io.github.xpertsavenue.ridevault.fit.FitParser
import io.github.xpertsavenue.ridevault.fit.FitRides
import io.github.xpertsavenue.ridevault.model.RideKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * iGPSPORT-Fahrradcomputer ab Generation 2 (Protobuf-Protokoll). Siehe docs/igpsport-protokoll.md.
 *
 * Hinweis: Aus dem App-Code abgeleitet und noch nicht an einem Gerät geprüft. Das Gerät wird beim Abruf **nicht** geleert.
 */
class IgpsportDriver(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : BikeComputerDriver {
    override val id = "igpsport"
    override val displayName = "iGPSPORT"

    // Nordic-UART-Dienste: Hauptkanal (…ca9e) für Befehle und Daten, Steuerkanal (…ca8e) für Bestätigungen.
    // Die Dienste unterscheiden sich nur im letzten Zeichen, deshalb wird die komplette UUID verglichen.
    override val gattProfile = GattProfile(
        serviceContains = "6e400001b5a3f393e0a9e50e24dcca9e",
        notifyContains = "6e400003b5a3f393e0a9e50e24dcca9e",
        writeContains = "6e400002b5a3f393e0a9e50e24dcca9e",
        preferredMtu = 247,
        controlServiceContains = "6e400001b5a3f393e0a9e50e24dcca8e",
        controlNotifyContains = "6e400003b5a3f393e0a9e50e24dcca8e",
        controlWriteContains = "6e400002b5a3f393e0a9e50e24dcca8e",
        // 3. und 4. UART-Kanal: die Original-App aktiviert bei Generation 3 alle vier, bevor sie den Kanal als "bereit" meldet
        extraNotifyContains = listOf("6e400003b5a3f393e0a9e50e24dcca7e", "6e400003b5a3f393e0a9e50e24dcca6e"),
        // Ohne Kopplung lehnt das Gerät alle Befehle ab (Bestätigung mit Status 1); die Original-App ruft createBond() auf
        requireBonding = true,
        extraWriteContains = "6e400002b5a3f393e0a9e50e24dcca7e",
        fourthWriteContains = "6e400002b5a3f393e0a9e50e24dcca6e",
    )

    override fun identify(scan: ScanInfo): DeviceModel? {
        val name = scan.name ?: return null
        val known = KNOWN_NAME_PREFIXES.firstOrNull { name.startsWith(it, ignoreCase = true) } ?: return null
        return DeviceModel("iGPSPORT", name.takeWhile { it != '_' && it != '-' }.ifEmpty { known })
    }

    override fun open(device: DeviceRef, channel: GattChannel): BikeComputerSession = IgpsportSession(device, channel, zone())

    companion object {
        /** BLE-Namen aus der App (BleConstants): BSC200, iGS10S_U, iGS320, iGS50S, iGS520(_U), iGS620, iGS630. */
        val KNOWN_NAME_PREFIXES = listOf("iGS", "BSC")
    }
}

class IgpsportProtocolException(message: String) : RuntimeException(message)

class IgpsportSession(
    private val device: DeviceRef,
    private val channel: GattChannel,
    private val zone: ZoneId,
    private val listTimeoutMs: Long = 10_000,
    private val listIdleMs: Long = 1_000,
    private val chunkTimeoutMs: Long = 15_000,
    private val handshakeTimeoutMs: Long = 3_000,
    private val configTimeoutMs: Long = 2_500,
    /**
     * Kopf und Daten als ein Strom auf dem Hauptkanal senden (Kopf zuerst), wie die Original-App bei Geräten ab Generation 4.
     * Beim BSC200 beobachtet: Sendet man die Daten getrennt vor dem Kopf, liest das Gerät sie als Kopfanfang und meldet nach einigen
     * Sekunden "Datenfehler" (Status 1).
     */
    private val mergedSend: Boolean = device.name.startsWith("BSC"),
) : BikeComputerSession, ConfigurableSession, PageConfigurableSession, RouteConfigurableSession, LiveSession {

    override val capabilities: Set<Capability> = setOf(Capability.SYNC_RIDES)

    /**
     * Geräteinformation (Standarddienst 180A): 2A28 Software, 2A27 Hardware, 2A29 Hersteller, 2A25 Seriennummer (falls vorhanden).
     * Akkustand liefert dieser Weg nicht.
     */
    override suspend fun deviceInfo(): DeviceInfo {
        suspend fun read(short: String) = channel.readValue("0000$short-0000-1000-8000-00805f9b34fb")
            ?.toString(Charsets.UTF_8)?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }
        val software = read("2a28")
        val hardware = read("2a27")
        return DeviceInfo(
            batteryPercent = null,
            firmware = software ?: hardware,
            firmwareDate = null,
            hardware = hardware.takeIf { software != null },
            manufacturer = read("2a29"),
            serial = read("2a25") ?: readSerialFromDevice(),
        )
    }

    /** Seriennummer über den Werksdienst (nur lesen). Das Gerät meldet sie nicht über die Standard-Geräteinformation. */
    private suspend fun readSerialFromDevice(): String? = runCatching {
        configMutex.withLock {
            withInbox { inbox, _ ->
                sendMessage(Service.FACTORY, FactoryMsg.SN_GET, FactoryMsg.requestSerial())
                withTimeoutOrNull(configTimeoutMs) {
                    var serial: String? = null
                    while (serial == null) {
                        val m = inbox.receive()
                        if (m.head.service == Service.FACTORY && m.dataCrcValid) serial = FactoryMsg.decodeSerial(m.data)
                    }
                    serial
                }
            }
        }
    }.getOrNull()

    override suspend fun apply(setting: DeviceSetting) {
        throw UnsupportedOperationException("Einstellungen werden für iGPSPORT noch nicht unterstützt")
    }

    /**
     * Sendet einen Befehl. Mit Steuerkanal wie die Original-App (`writeProtoBufWithControlCommand`): **erst die Nutzlast auf den
     * Datenkanal, danach der 20-Byte-Kopf auf den Steuerkanal**. Ohne Steuerkanal als ein Strom: Kopf, dann Daten.
     */
    private suspend fun send(msg: CyclingDataMsg) = sendMessage(Service.CYCLING_DATA, msg.operation, msg.encode())

    private suspend fun sendMessage(service: Int, operation: Int, data: ByteArray, second: Int = 0xFF) {
        // Original-App (readActivityFitFile, Generation 4): beim Datei-Abruf steht im Kopf an Byte 3 der Wert 0x55 statt 0xFF
        val fileTag = if (mergedSend && service == Service.CYCLING_DATA && operation == CyclingOp.FILE_GET) 0x55 else 0xFF
        val head = Head20.build(service, operation, data, fileTag = fileTag, secondService = second)
        if (channel.hasControlChannel && !mergedSend) {
            if (data.isNotEmpty()) channel.write(data)
            channel.writeControl(head)
        } else if (mergedSend) {
            // Fahrten-Befehle gehen bei neueren Geräten auf den 3. UART-Kanal (Original-App: Kanal 3 ab Generation 3), die übrigen auf den Hauptkanal
            when (service) {
                Service.CYCLING_DATA -> channel.writeExtra(head + data)
                // Strecken und Dateien nutzt die Hersteller-App auf dem vierten Kanal
                Service.ROUTE_PLAN, Service.FILE_OPERATION -> channel.writeFourth(head + data)
                else -> channel.write(head + data)
            }
        } else {
            channel.write(head)
            if (data.isNotEmpty()) channel.write(data)
        }
    }

    // ---- Einstellungen (config.proto) ---------------------------------------------------------------------------------

    private val configMutex = Mutex()

    /** Der Anmelde-Handshake ist in dieser Verbindung schon gelaufen; Einstellungen und Seiten wiederholen ihn nicht. */
    @Volatile private var handshaken = false

    /** false, sobald das Gerät GET_MODULE_INFO für Seiten ablehnt (BSC200: Status 6 = nicht unterstützt). */
    @Volatile private var pageInfoSupported = true
    private var cachedBacklight = BacklightData()
    private var cachedUser = UserData()
    private val cachedAutoParam = HashMap<Int, Int?>()

    override val configItems: List<ConfigItem> = buildList {
        val unitChoice = ConfigKind.Choice(listOf(1, 2))
        add(ConfigItem("unit.distance", "units", unitChoice))
        add(ConfigItem("unit.elevation", "units", unitChoice))
        add(ConfigItem("unit.weight", "units", unitChoice))
        add(ConfigItem("unit.temperature", "units", unitChoice))
        add(ConfigItem("backlight.auto", "display", ConfigKind.Toggle))
        add(ConfigItem("backlight.night", "display", ConfigKind.Toggle))
        add(ConfigItem("backlight.time", "display", ConfigKind.Choice(listOf(10, 15, 30, 60, 300, 600, -1))))
        add(ConfigItem("backlight.day", "display", ConfigKind.Number(0, 100, 10)))
        add(ConfigItem("backlight.night_level", "display", ConfigKind.Number(0, 100, 10)))
        listOf("sound.key", "sound.alert", "sound.record_start", "sound.record_pause", "sound.record_save", "sound.lap")
            .forEach { add(ConfigItem(it, "sound", ConfigKind.Toggle)) }
        listOf("auto.pause", "auto.record", "auto.power_off", "auto.sleep", "auto.home", "auto.smart")
            .forEach { add(ConfigItem(it, "auto", ConfigKind.Toggle)) }
        add(ConfigItem("auto.power_save", "auto", ConfigKind.Choice(listOf(0, 1, 2))))
        add(ConfigItem("auto.page", "auto", ConfigKind.Choice(listOf(0, 1, 2, 3, 4))))
        add(ConfigItem("language", "language", ConfigKind.Choice((1..38).toList())))
        add(ConfigItem("user.sex", "user", ConfigKind.Choice(listOf(1, 0))))
        add(ConfigItem("user.age", "user", ConfigKind.Number(10, 100)))
        add(ConfigItem("user.height", "user", ConfigKind.Number(100, 230)))
        add(ConfigItem("user.weight", "user", ConfigKind.Number(30, 200)))
    }

    private val autoIds = mapOf(
        "auto.pause" to AutoType.PAUSE, "auto.record" to AutoType.RECORD_START, "auto.power_off" to AutoType.POWER_OFF,
        "auto.sleep" to AutoType.SLEEP, "auto.home" to AutoType.HOME_PAGE_BACK, "auto.smart" to AutoType.SMART_SAVE,
        "auto.power_save" to AutoType.POWER_SAVE, "auto.page" to AutoType.PAGE_AUTO,
    )
    private val soundIds = mapOf(
        "sound.key" to SoundType.KEY, "sound.alert" to SoundType.ALERT, "sound.record_start" to SoundType.RECORD_START,
        "sound.record_pause" to SoundType.RECORD_PAUSE, "sound.record_save" to SoundType.RECORD_SAVE, "sound.lap" to SoundType.LAP,
    )
    private val unitIds = mapOf(
        "unit.distance" to UnitItem.DISTANCE, "unit.elevation" to UnitItem.ELEVATION,
        "unit.weight" to UnitItem.WEIGHT, "unit.temperature" to UnitItem.TEMPERATURE,
    )

    /** Liest alle Einstellungen, die das Gerät meldet. Meldet es eine Gruppe nicht, fehlen ihre Werte (die Oberfläche blendet sie aus). */
    override suspend fun readConfig(): ConfigState = configMutex.withLock {
        withInbox { inbox, confirms ->
            val values = HashMap<String, Int>()
            val choices = HashMap<String, List<Int>>()
            // Lehnt das Gerät eine Gruppe ab (am BSC200 z. B. die Sprache: Status 6 = nicht unterstützt), kommt statt der Antwort
            // ein Bestätigungspaket mit Fehlerstatus. Dann nicht bis zum Zeitlimit warten, sondern sofort weiter.
            suspend fun get(service: Int): ConfigMsg? {
                sendMessage(Service.CONFIG, ConfigOp.GET, ConfigMsg(service, ConfigOp.GET).encode(), second = service)
                return withTimeoutOrNull(configTimeoutMs) {
                    while (true) {
                        val x = select<Any> {
                            confirms.onReceive { it }
                            inbox.onReceive { it }
                        }
                        if (x is Head20 && x.service == Service.CONFIG && x.secondService == service && x.confirmStatus != 0) return@withTimeoutOrNull null
                        if (x is Message && x.head.service == Service.CONFIG && x.dataCrcValid) {
                            val decoded = runCatching { ConfigMsg.decode(x.data) }.getOrNull()
                            if (decoded != null && decoded.configService == service) return@withTimeoutOrNull decoded
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            }
            get(ConfigService.UNIT)?.units?.forEach { (item, type) ->
                unitIds.entries.firstOrNull { it.value == item }?.let { values[it.key] = type }
            }
            get(ConfigService.BACKLIGHT)?.backlight?.let { b ->
                cachedBacklight = b
                b.auto?.let { values["backlight.auto"] = it }
                b.nightOn?.let { values["backlight.night"] = it }
                b.timeSeconds?.let { values["backlight.time"] = it.toInt() }
                b.dayPercent?.let { values["backlight.day"] = it }
                b.nightPercent?.let { values["backlight.night_level"] = it }
            }
            get(ConfigService.SOUND)?.sounds?.forEach { s ->
                soundIds.entries.firstOrNull { it.value == s.type }?.let { values[it.key] = s.status }
            }
            get(ConfigService.AUTO)?.autos?.forEach { a ->
                cachedAutoParam[a.type] = a.param1
                autoIds.entries.firstOrNull { it.value == a.type }?.let { values[it.key] = a.status }
            }
            get(ConfigService.LANG)?.let { m ->
                m.language?.let { values["language"] = it }
                if (m.supportedLanguages.isNotEmpty()) choices["language"] = m.supportedLanguages
            }
            get(ConfigService.USER)?.user?.let { u ->
                cachedUser = u
                u.sex?.let { values["user.sex"] = it }
                u.age?.let { values["user.age"] = it }
                u.height?.let { values["user.height"] = it }
                u.weight100g?.let { values["user.weight"] = (it + 5) / 10 }
            }
            ConfigState(values, choices)
        }
    }

    /** Schreibt eine Einstellung. Gruppen, die das Gerät nur im Ganzen annimmt (Beleuchtung, Profil), werden mit den zuletzt gelesenen Werten ergänzt. */
    override suspend fun writeConfig(id: String, value: Int) {
        configMutex.withLock {
            val msg: ConfigMsg = when {
                id in unitIds -> ConfigMsg(ConfigService.UNIT, ConfigOp.SET, units = listOf(unitIds.getValue(id) to value))
                id in soundIds -> ConfigMsg(ConfigService.SOUND, ConfigOp.SET, sounds = listOf(SoundItem(soundIds.getValue(id), 1, value)))
                id in autoIds -> {
                    val type = autoIds.getValue(id)
                    ConfigMsg(ConfigService.AUTO, ConfigOp.SET, autos = listOf(AutoItem(type, value, cachedAutoParam[type])))
                }
                id == "language" -> ConfigMsg(ConfigService.LANG, ConfigOp.SET, language = value)
                id.startsWith("backlight.") -> {
                    val b = cachedBacklight
                    val next = when (id) {
                        "backlight.auto" -> b.copy(auto = value)
                        "backlight.night" -> b.copy(nightOn = value)
                        "backlight.time" -> b.copy(timeSeconds = value.toLong())
                        "backlight.day" -> b.copy(dayPercent = value)
                        else -> b.copy(nightPercent = value)
                    }
                    ConfigMsg(ConfigService.BACKLIGHT, ConfigOp.SET, backlight = next)
                }
                id.startsWith("user.") -> {
                    val u = cachedUser
                    val next = when (id) {
                        "user.sex" -> u.copy(sex = value)
                        "user.age" -> u.copy(age = value)
                        "user.height" -> u.copy(height = value)
                        else -> u.copy(weight100g = value * 10)
                    }
                    ConfigMsg(ConfigService.USER, ConfigOp.SET, user = next)
                }
                else -> throw IllegalArgumentException("Unbekannte Einstellung: $id")
            }
            withInbox { inbox, confirms -> sendSet(inbox, confirms, msg) }
            // Zwischenspeicher nachführen, damit die nächste Änderung derselben Gruppe nichts zurücksetzt
            if (id.startsWith("backlight.")) msg.backlight?.let { cachedBacklight = it }
            if (id.startsWith("user.")) msg.user?.let { cachedUser = it }
        }
    }

    /** Sendet eine SET-Nachricht und wartet auf Antwort oder Bestätigung; lehnt das Gerät ab, wird eine Ausnahme geworfen. */
    private suspend fun sendSet(inbox: Channel<Message>, confirms: Channel<Head20>, msg: ConfigMsg) {
        sendMessage(Service.CONFIG, ConfigOp.SET, msg.encode(), second = msg.configService)
        val answer = withTimeoutOrNull(configTimeoutMs) {
            select<Any> {
                confirms.onReceive { it }
                inbox.onReceive { it }
            }
        }
        if (answer is Head20 && answer.service == Service.CONFIG && answer.confirmStatus != 0) {
            throw IgpsportProtocolException("Das Gerät hat die Einstellung abgelehnt (Status ${answer.confirmStatus})")
        }
    }

    private suspend fun nextConfig(inbox: Channel<Message>, service: Int, accept: (ConfigMsg) -> Boolean = { true }): ConfigMsg {
        while (true) {
            val m = inbox.receive()
            if (m.head.service != Service.CONFIG || !m.dataCrcValid) continue
            val decoded = runCatching { ConfigMsg.decode(m.data) }.getOrNull() ?: continue
            if (decoded.configService == service && accept(decoded)) return decoded
        }
    }

    // ---- Strecken (Navigation) --------------------------------------------------------------------------------------------

    override suspend fun readRoutes(): List<DeviceRoute> = configMutex.withLock {
        withInbox { inbox, confirms ->
            /** Sammelt Antworten des Streckendienstes, bis nichts mehr kommt; ein Fehlerstatus des Geräts beendet sofort mit Ausnahme. */
            suspend fun collect(firstWaitMs: Long): List<RoutePlanMsg> {
                val out = ArrayList<RoutePlanMsg>()
                while (true) {
                    val x = withTimeoutOrNull(if (out.isEmpty()) firstWaitMs else 800L) {
                        select<Any> {
                            confirms.onReceive { it }
                            inbox.onReceive { it }
                        }
                    } ?: return out
                    if (x is Head20 && x.service == Service.ROUTE_PLAN && x.confirmStatus != 0) {
                        throw IgpsportProtocolException("Das Gerät unterstützt die Streckenliste nicht (Status ${x.confirmStatus})")
                    }
                    if (x is Message && x.head.service == Service.ROUTE_PLAN && x.dataCrcValid) {
                        runCatching { RoutePlanMsg.decode(x.data) }.getOrNull()?.let { out += it }
                    }
                }
            }

            // 1) Anzahl (und wie viele Einträge das Gerät je Nachricht liefert) – wie die Hersteller-App
            sendMessage(Service.ROUTE_PLAN, RouteOp.LIST_NUM_GET, RoutePlanMsg(RouteOp.LIST_NUM_GET).encode())
            val range = collect(configTimeoutMs).firstNotNullOfOrNull { it.range }
            val total = range?.fileNum ?: 0
            if (total == 0) return@withInbox emptyList()
            val page = (range?.supportMax ?: 0).takeIf { it > 0 } ?: total

            // 2) Liste in Abschnitten abfragen
            val routes = ArrayList<RouteInfoMsg>()
            var start = 0
            while (start < total) {
                val end = minOf(start + page, total) - 1
                sendMessage(
                    Service.ROUTE_PLAN, RouteOp.LIST_GET,
                    RoutePlanMsg(RouteOp.LIST_GET, range = ListRange(fileNum = total, supportMax = page, start = start, end = end)).encode(),
                )
                collect(configTimeoutMs * 2).forEach { routes += it.infos }
                start = end + 1
            }
            routes.filter { it.id != null }.map {
                DeviceRoute(it.id!!, it.name, it.fileType, it.totalDistanceM, it.latitudeStart, it.longitudeStart, inUse = it.status?.let { s -> s == 1 })
            }
        }
    }

    override suspend fun sendRoute(name: String, extension: String, content: ByteArray, onProgress: (Int) -> Unit) {
        configMutex.withLock {
            withInbox { inbox, confirms ->
                // Kennung der Strecke: Sekunden seit Epoche (vom Gerät nur als eindeutige Nummer benutzt)
                val stream = FileOperation.buildStream((System.currentTimeMillis() / 1000).toInt(), name, extension.lowercase(), content)
                onProgress(0)
                channel.writeFourth(stream, onProgress)
                // Antwort: Dienst 21, Operation 3, Statusbyte 0 = in Ordnung (als Bestätigung oder als Nachricht)
                val status = withTimeoutOrNull(20_000) {
                    var result: Int? = null
                    while (result == null) {
                        val x = select<Any> {
                            confirms.onReceive { it }
                            inbox.onReceive { it }
                        }
                        if (x is Head20 && x.service == Service.FILE_OPERATION) result = x.confirmStatus
                        if (x is Message && x.head.service == Service.FILE_OPERATION) result = x.head.confirmStatus
                    }
                    result
                } ?: throw IgpsportProtocolException("Keine Antwort des Geräts auf die Strecke")
                if (status != 0) throw IgpsportProtocolException("Das Gerät hat die Strecke abgelehnt (Status $status)")
            }
        }
    }

    /**
     * Löscht wie die Hersteller-App: Operation "mehrere Dateien löschen" (6), je Strecke die Dateikennung "Nummer.Endung" in `line_id`
     * und die Beschreibung (Nummer, Typ, Name). Der Einzelbefehl ohne Dateikennung wird vom Gerät nur quittiert, aber nicht ausgeführt.
     */
    override suspend fun deleteRoute(route: DeviceRoute) {
        configMutex.withLock {
            withInbox { inbox, confirms ->
                val info = RouteInfoMsg(id = route.id, fileType = route.fileType, name = route.name ?: route.id.toString(), totalDistanceM = 0)
                val msg = RoutePlanMsg(RouteOp.FILES_DEL, listOf(info), lineIds = listOf("${route.id}.${routeExtension(route.fileType)}"))
                sendMessage(Service.ROUTE_PLAN, RouteOp.FILES_DEL, msg.encode())
                val answer = withTimeoutOrNull(configTimeoutMs) {
                    select<Any> {
                        confirms.onReceive { it }
                        inbox.onReceive { it }
                    }
                }
                if (answer is Head20 && answer.service == Service.ROUTE_PLAN && answer.confirmStatus != 0) {
                    throw IgpsportProtocolException("Das Gerät hat das Löschen abgelehnt (Status ${answer.confirmStatus})")
                }
            }
        }
    }

    /**
     * Wie die Hersteller-App (`setRoutePlanFile`): Operation "Strecke benutzen" (5) mit Dateikennung "Nummer.Endung" in `line_id`
     * und der Beschreibung (Nummer, Typ, Name = Nummer, Länge 0).
     */
    override suspend fun useRoute(route: DeviceRoute) {
        configMutex.withLock {
            withInbox { inbox, confirms ->
                val info = RouteInfoMsg(id = route.id, fileType = route.fileType, name = route.id.toString(), totalDistanceM = 0)
                val msg = RoutePlanMsg(RouteOp.FILE_USE, listOf(info), lineIds = listOf("${route.id}.${routeExtension(route.fileType)}"))
                sendMessage(Service.ROUTE_PLAN, RouteOp.FILE_USE, msg.encode())
                val answer = withTimeoutOrNull(configTimeoutMs) {
                    select<Any> {
                        confirms.onReceive { it }
                        inbox.onReceive { it }
                    }
                }
                if (answer is Head20 && answer.service == Service.ROUTE_PLAN && answer.confirmStatus != 0) {
                    throw IgpsportProtocolException("Das Gerät hat die Strecke abgelehnt (Status ${answer.confirmStatus})")
                }
            }
        }
    }

    override suspend fun renameRoute(route: DeviceRoute, newName: String) {
        val info = RouteInfoMsg(id = route.id, fileType = route.fileType, name = newName, totalDistanceM = 0)
        val msg = RoutePlanMsg(RouteOp.RENAME, listOf(info), lineIds = listOf("${route.id}.${routeExtension(route.fileType)}"))
        routeCommand(RouteOp.RENAME, msg)
    }

    private suspend fun routeCommand(op: Int, msg: RoutePlanMsg) {
        configMutex.withLock {
            withInbox { inbox, confirms ->
                sendMessage(Service.ROUTE_PLAN, op, msg.encode())
                val answer = withTimeoutOrNull(configTimeoutMs) {
                    select<Any> {
                        confirms.onReceive { it }
                        inbox.onReceive { it }
                    }
                }
                if (answer is Head20 && answer.service == Service.ROUTE_PLAN && answer.confirmStatus != 0) {
                    throw IgpsportProtocolException("Das Gerät hat den Befehl abgelehnt (Status ${answer.confirmStatus})")
                }
            }
        }
    }

    // ---- Livewerte und Gerätezustand ------------------------------------------------------------------------------------

    /** Sendet eine Anfrage und wartet auf die erste passende Antwort des Dienstes; null bei Ablehnung oder Zeitüberschreitung. */
    private suspend fun <T> requestReply(service: Int, op: Int, data: ByteArray, parse: (Message) -> T?): T? = configMutex.withLock {
        withInbox { inbox, confirms ->
            sendMessage(service, op, data)
            withTimeoutOrNull(configTimeoutMs) {
                var found: T? = null
                while (found == null) {
                    val x = select<Any> {
                        confirms.onReceive { it }
                        inbox.onReceive { it }
                    }
                    if (x is Head20 && x.service == service && x.confirmStatus != 0) return@withTimeoutOrNull null
                    if (x is Message && x.head.service == service && x.dataCrcValid) found = parse(x)
                }
                found
            }
        }
    }

    private fun rideState(v: Int?) = when (v) { 0 -> RideState.IDLE; 1 -> RideState.RIDING; 2 -> RideState.PAUSED; else -> null }

    private suspend fun readStatus(): DevStatusMsg? =
        requestReply(Service.DEV_STATUS, DevStatusOp.GET, DevStatusMsg.requestGet()) { runCatching { DevStatusMsg.decode(it.data) }.getOrNull() }

    override suspend fun readLive(): LiveData? {
        val st = readStatus() ?: return null
        val rt = st.rt
        return LiveData(
            rideState = rideState(st.cyclingStatus),
            navigating = st.naviStatus?.let { it == 1 },
            speedKmh = rt?.speed?.let { it * 0.0036 }, avgSpeedKmh = rt?.avgSpeed?.let { it * 0.0036 }, maxSpeedKmh = rt?.maxSpeed?.let { it * 0.0036 },
            timeSec = rt?.ridingTime?.let { it / 1000 }, distanceKm = rt?.distance?.let { it / 100_000.0 },
            cadence = rt?.cadence, avgCadence = rt?.avgCadence, maxCadence = rt?.maxCadence,
            heartRate = rt?.heartRate, avgHeartRate = rt?.avgHeartRate, maxHeartRate = rt?.maxHeartRate,
            power = rt?.power, avgPower = rt?.avgPower, maxPower = rt?.maxPower,
            altitudeM = rt?.height?.let { it / 100.0 }, slopePercent = rt?.slope?.let { it / 100.0 }, ascentM = rt?.totalAscent,
            latitude = st.latitude?.takeIf { it != 0.0 }, longitude = st.longitude?.takeIf { it != 0.0 },
        )
    }

    override suspend fun readHealth(): DeviceHealth {
        val battery = requestReply(Service.FACTORY, FactoryMsg.BATTERY_GET, FactoryMsg.request(FactoryMsg.BATTERY_GET)) { FactoryMsg.decodeBattery(it.data) }
        val memory = requestReply(Service.FACTORY, FactoryMsg.MEMORY_GET, FactoryMsg.request(FactoryMsg.MEMORY_GET)) { FactoryMsg.decodeMemory(it.data) }
        val st = readStatus()
        return DeviceHealth(
            batteryPercent = battery?.second, batteryMillivolt = battery?.first,
            storageFreePercent = memory?.let { (total, remain) -> if (total != null && remain != null && total > 0) (remain * 100 / total).toInt() else null },
            rideState = rideState(st?.cyclingStatus), navigating = st?.naviStatus?.let { it == 1 },
        )
    }

    // ---- Seiten (Datenfelder und Anordnung) ----------------------------------------------------------------------------

    override suspend fun readRidingModes(): List<RidingMode> = configMutex.withLock {
        withInbox { inbox, _ ->
            sendMessage(Service.CONFIG, ConfigOp.GET, ConfigMsg(ConfigService.MODE, ConfigOp.GET).encode(), second = ConfigService.MODE)
            val reply = withTimeoutOrNull(configTimeoutMs) { nextConfig(inbox, ConfigService.MODE) { it.modes.isNotEmpty() } }
            reply?.modes.orEmpty().filter { it.index != null }.map {
                RidingMode(it.index!!, it.name, inUse = it.inUse == 1, valid = it.valid != 0)
            }
        }
    }

    override suspend fun readPages(mode: Int?): DevicePages = configMutex.withLock {
        withInbox { inbox, confirms ->
            // Seiten (GET) und Fähigkeiten (GET_MODULE_INFO) getrennt abfragen; die Seitenliste kann in mehreren Nachrichten kommen
            sendMessage(Service.CONFIG, ConfigOp.GET, ConfigMsg(ConfigService.PAGE, ConfigOp.GET, operateMode = mode).encode(), second = ConfigService.PAGE)
            val pages = ArrayList<PageMsg>()
            var status: PageStatusMsg? = null
            var first = withTimeoutOrNull(configTimeoutMs) { nextConfig(inbox, ConfigService.PAGE) { it.pages.isNotEmpty() || it.pageStatus != null } }
                ?: throw IgpsportProtocolException("Das Gerät hat keine Seiten gemeldet")
            while (true) {
                pages += first.pages
                first.pageStatus?.let { status = it }
                first = withTimeoutOrNull(700) { nextConfig(inbox, ConfigService.PAGE) { it.pages.isNotEmpty() || it.pageStatus != null } } ?: break
            }
            if (status == null && pageInfoSupported) {
                sendMessage(Service.CONFIG, ConfigOp.GET_MODULE_INFO, ConfigMsg(ConfigService.PAGE, ConfigOp.GET_MODULE_INFO).encode(), second = ConfigService.PAGE)
                status = withTimeoutOrNull(1_500) {
                    var found: PageStatusMsg? = null
                    while (found == null) {
                        val x = select<Any> {
                            confirms.onReceive { it }
                            inbox.onReceive { it }
                        }
                        if (x is Head20 && x.service == Service.CONFIG && x.confirmStatus != 0) {
                            pageInfoSupported = false // abgelehnt (am BSC200 "nicht unterstützt"): künftig nicht mehr fragen
                            return@withTimeoutOrNull null
                        }
                        if (x is Message && x.head.service == Service.CONFIG && x.dataCrcValid) {
                            found = runCatching { ConfigMsg.decode(x.data) }.getOrNull()?.pageStatus
                        }
                    }
                    found
                }
                if (status == null) pageInfoSupported = false
            }
            val st = status
            DevicePages(
                pages = pages.filter { it.index != null }.map {
                    DevicePage(
                        index = it.index!!, enabled = it.status == 1, type = it.type ?: 1, data = it.data, layout = it.mode ?: 0,
                        lineWidth = it.lineWidth ?: 0, name = it.name, dataSite = it.dataSite, mainPage = it.mainPage == 1, graphicDisplay = it.graphicDisplay,
                    )
                }.sortedBy { it.index },
                capabilities = PageCapabilities(
                    maxRows = st?.lineMax, maxPerRow = st?.lineDataMax, maxDataPages = st?.dataPageMax,
                    unsupportedData = st?.unsupportedData.orEmpty().toSet(), layouts = st?.layouts.orEmpty(),
                ),
            )
        }
    }

    override suspend fun writePages(mode: Int?, pages: List<DevicePage>) {
        configMutex.withLock {
            val msg = ConfigMsg(
                ConfigService.PAGE, ConfigOp.SET, operateMode = mode,
                pages = pages.map {
                    PageMsg(
                        index = it.index, status = if (it.enabled) 1 else 0, data = it.data, dataSite = it.dataSite, name = it.name,
                        type = it.type, mode = it.layout, mainPage = if (it.mainPage) 1 else null, lineWidth = it.lineWidth,
                        graphicDisplay = it.graphicDisplay,
                    )
                },
            )
            withInbox { inbox, confirms -> sendSet(inbox, confirms, msg) }
        }
    }

    /**
     * Öffnet für die Dauer von [block] die Empfangskanäle (Haupt-, Zusatz- und Steuerkanal), führt den Anmelde-Handshake aus und liefert
     * Nachrichten und Bestätigungen des Geräts. Meldungen anderer Dienste auf dem Steuerkanal werden wie beim Abruf der Fahrten bestätigt.
     */
    private suspend fun <T> withInbox(block: suspend (Channel<Message>, Channel<Head20>) -> T): T = coroutineScope {
        val inbox = Channel<Message>(Channel.UNLIMITED)
        val confirms = Channel<Head20>(Channel.UNLIMITED)
        val main = FrameAssembler { confirms.trySend(it) }
        val extra = FrameAssembler { confirms.trySend(it) }
        val j1 = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.notifications.collect { chunk -> main.feed(chunk).forEach { inbox.trySend(it) } }
        }
        val j2 = launch(start = CoroutineStart.UNDISPATCHED) {
            channel.extraNotifications.collect { chunk -> extra.feed(chunk).forEach { inbox.trySend(it) } }
        }
        val j3 = if (channel.hasControlChannel) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                channel.controlNotifications.collect { chunk ->
                    val head = Head20.parse(chunk)
                    if (head != null && head.headerCrcValid && head.type == Head20.TYPE_TRANSMIT_COMPLETE &&
                        head.service != Service.CYCLING_DATA && head.service != Service.BLE
                    ) {
                        runCatching { channel.writeControl(Head20.confirm(head.service, head.operation, 0)) }
                    }
                }
            }
        } else null
        try {
            if (!handshaken) handshake(inbox, confirms)
            block(inbox, confirms)
        } finally {
            j1.cancel(); j2.cancel(); j3?.cancel()
        }
    }

    private suspend fun confirm(status: Int) =
        channel.writeControl(Head20.confirm(Service.CYCLING_DATA, CyclingOp.FILE_SEND, status))

    override fun syncRides(isKnown: suspend (RideKey) -> Boolean): Flow<SyncEvent> = flow {
        coroutineScope {
            val inbox = Channel<Message>(Channel.UNLIMITED)
            // Mit Steuerkanal kommen Daten und Kopf getrennt an, sonst als ein Strom
            val split = channel.hasControlChannel
            // Lehnt das Gerät einen Fahrten-Befehl ab, endet die Synchronisation sofort mit klarer Meldung (statt nach einem Timeout)
            val confirms = Channel<Head20>(Channel.UNLIMITED)
            val onConfirm: (Head20) -> Unit = { head ->
                confirms.trySend(head)
                if (head.service == Service.CYCLING_DATA && head.confirmStatus != 0) {
                    inbox.close(
                        IgpsportProtocolException(
                            "Das Gerät hat den Befehl abgelehnt (Dienst ${head.service}, Operation ${head.operation}, Status ${head.confirmStatus}). " +
                                "Vermutlich muss das Gerät zuerst gekoppelt werden.",
                        ),
                    )
                }
            }
            // Im Strommodus (Kopf + Daten zusammen, wie beim BSC200) antwortet das Gerät ebenso: je Kanal ein Strom, Antworten kommen auf Haupt- und Zusatzkanal
            val stream = !split || mergedSend
            val receiver = SplitReceiver(onConfirm)
            val assembler = FrameAssembler(onConfirm)
            val extraAssembler = FrameAssembler(onConfirm)
            val rawFit = RawFitStream()
            // Roher FIT-Strom (Generation 4): als fertige FILE_SEND-Nachricht weiterreichen, damit der Download-Ablauf gleich bleibt
            fun rawFile(chunk: ByteArray): Boolean {
                val fit = when {
                    rawFit.active -> rawFit.feed(chunk)
                    rawFit.isStartHeader(chunk) -> rawFit.start(chunk)
                    else -> return false
                }
                // Fortschrittsmeldung: setzt das Zeitlimit des Downloads zurück (große Dateien brauchen länger als ein Zeitlimit)
                if (fit == null) {
                    val n = rawFit.receivedBytes
                    inbox.trySend(Message(Head20(Head20.build(Service.BLE, 0, ByteArray(0))), byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())))
                }
                if (fit != null) {
                    val data = CyclingDataMsg(CyclingOp.FILE_SEND, fileContent = fit).encode()
                    // Interner Kopf ohne Längenbegrenzung (rohe Dateien können größer als 64 KB sein): nur Dienst, Operation und Daten-CRC werden gebraucht
                    val head = Head20.build(Service.CYCLING_DATA, CyclingOp.FILE_SEND, ByteArray(0), type = Head20.TYPE_TRANSMIT_COMPLETE)
                    head[9] = Crc8.calc(data).toByte()
                    head[19] = Crc8.calc(head, 0, 19).toByte()
                    inbox.trySend(Message(Head20(head), data))
                }
                return true
            }
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                channel.notifications.collect { chunk ->
                    if (stream && rawFile(chunk)) return@collect
                    (if (stream) assembler.feed(chunk) else receiver.onData(chunk)).forEach { inbox.trySend(it) }
                }
            }
            val extraCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                channel.extraNotifications.collect { chunk ->
                    if (rawFile(chunk)) return@collect
                    extraAssembler.feed(chunk).forEach { inbox.trySend(it) }
                }
            }
            val controlCollector = if (split) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    channel.controlNotifications.collect { chunk ->
                        if (!stream) receiver.onControl(chunk).forEach { inbox.trySend(it) }
                        // Meldungen anderer Dienste (z. B. Dienst 3) wiederholt das Gerät, bis sie bestätigt sind – und weist solange Befehle ab
                        val head = Head20.parse(chunk)
                        if (head != null && head.headerCrcValid && head.type == Head20.TYPE_TRANSMIT_COMPLETE &&
                            head.service != Service.CYCLING_DATA && head.service != Service.BLE
                        ) {
                            runCatching { channel.writeControl(Head20.confirm(head.service, head.operation, 0)) }
                        }
                    }
                }
            } else null
            try {
                handshake(inbox, confirms)
                send(CyclingDataMsg(CyclingOp.LIST_GET))
                val files = receiveList(inbox)
                emit(SyncEvent.Started(files.size))
                val todo = files.count { !isKnown(RideKey(device.address, fileStart(it.timestamp))) }
                var number = 0

                for (file in files) {
                    val start = fileStart(file.timestamp)
                    if (isKnown(RideKey(device.address, start))) {
                        emit(SyncEvent.RideSkipped(start))
                        continue
                    }
                    emit(SyncEvent.Loading(++number, todo, start))
                    val fit = download(inbox, file) { received, total -> emit(SyncEvent.Progress(received, total)) }
                    val activity = FitParser.parse(fit)
                    emit(SyncEvent.RideReceived(FitRides.toRide(device.address, activity, zone, startOverride = start)))
                }
                emit(SyncEvent.Finished)
            } finally {
                collector.cancel()
                controlCollector?.cancel()
                extraCollector.cancel()
            }
        }
    }

    /**
     * Anmelde-Handshake wie `afterDeviceReadyForBindDevice` der Original-App: Bindungsinformation lesen; meldet das Gerät "gebunden"
     * mit einer Mitglieds-ID, wird der Verbindungsstatus mit **derselben ID** zurückgemeldet (`sendBondingStatus`).
     * Antwortet das Gerät nicht, geht es ohne Handshake weiter. Ein ungebundenes Gerät wird NICHT gebunden (BOND_REQ wird nie gesendet).
     */
    private suspend fun handshake(inbox: Channel<Message>, confirms: Channel<Head20>) {
        sendMessage(Service.BLE, BleOp.BOND_INFO, BleMsg(BleOp.BOND_INFO).encode())
        val info = withTimeoutOrNull(handshakeTimeoutMs) { nextOfService(inbox, Service.BLE) }
        val bond = info?.let { runCatching { BleMsg.decode(it.data).data.firstOrNull() }.getOrNull() }
        // Meldet das Gerät eine eigene Mitglieds-ID, wird sie zurückgemeldet; sonst nutzt die App eine lokale ID (statt des iGPSPORT-Kontos).
        val member = bond?.memberId?.takeIf { it.isNotEmpty() } ?: LOCAL_MEMBER_ID
        sendMessage(Service.BLE, BleOp.CONNECT_STATUS, BleMsg(BleOp.CONNECT_STATUS, listOf(BleData(1, member))).encode())
        // Der BSC200 quittiert den Verbindungsstatus nur mit einem Bestätigungspaket (keine Nachricht): auch das beendet das Warten,
        // sonst vergingen bei jedem Befehl drei Sekunden bis zum Zeitlimit.
        withTimeoutOrNull(handshakeTimeoutMs) {
            while (true) {
                val x = select<Any> {
                    confirms.onReceive { it }
                    inbox.onReceive { it }
                }
                if (x is Head20 && x.service == Service.BLE) break
                if (x is Message && x.head.service == Service.BLE && x.dataCrcValid) break
            }
        }
        handshaken = true
    }

    private suspend fun nextOfService(inbox: Channel<Message>, service: Int): Message {
        while (true) {
            val m = inbox.receive()
            if (m.head.service == service && m.dataCrcValid) return m
        }
    }

    /** Erste LIST_SEND-Nachricht abwarten, danach kurz auf weitere (Paging) warten. */
    private suspend fun receiveList(inbox: Channel<Message>): List<FileFlag> {
        val first = withTimeoutOrNull(listTimeoutMs) { nextOf(inbox, CyclingOp.LIST_SEND) }
            ?: throw IgpsportProtocolException("Keine Antwort auf die Fahrtenliste")
        val files = LinkedHashMap<Long, FileFlag>()
        fun add(m: Message) = CyclingDataMsg.decode(m.data).files.forEach { files[it.timestamp] = it }
        add(first)
        while (true) {
            val more = withTimeoutOrNull(listIdleMs) { nextOf(inbox, CyclingOp.LIST_SEND) } ?: break
            add(more)
        }
        return files.values.sortedBy { it.timestamp }
    }

    private suspend fun nextOf(inbox: Channel<Message>, operation: Int): Message {
        while (true) {
            val m = inbox.receive()
            if (m.head.service == Service.CYCLING_DATA && m.head.operation == operation && m.dataCrcValid) return m
        }
    }

    /** Lädt eine FIT-Datei: jeder gültige Block wird bestätigt, ein fehlerhafter mit Status 1 (Gerät sendet ihn neu). */
    private suspend fun download(
        inbox: Channel<Message>,
        file: FileFlag,
        onProgress: suspend (received: Long, total: Long) -> Unit,
    ): ByteArray {
        send(CyclingDataMsg(CyclingOp.FILE_GET, files = listOf(FileFlag(file.timestamp))))
        val buffer = ByteArrayOutputStream()
        val expected = file.fileSize?.takeIf { it > 0 }
        while (expected == null || buffer.size() < expected) {
            val m = try {
                withTimeout(chunkTimeoutMs) { inbox.receive() }
            } catch (e: TimeoutCancellationException) {
                // Ohne bekannte Größe gilt eine Pause nach vorhandenen Daten als Ende der Datei
                if (expected == null && buffer.size() > 0) break
                throw IgpsportProtocolException("Zeitüberschreitung beim Empfang der Fahrt (${buffer.size()} von ${expected ?: "?"} Byte)")
            }
            if (m.head.service == Service.BLE && m.head.operation == 0 && m.data.size == 4) { // Fortschritt des rohen Datenstroms (Bytes inkl. Vorspann)
                val n = m.data.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                if (expected != null) onProgress(minOf(n, expected), expected)
                continue
            }
            if (m.head.service != Service.CYCLING_DATA || m.head.operation != CyclingOp.FILE_SEND) continue
            if (!m.dataCrcValid) { if (!mergedSend) confirm(status = 1); continue }
            val msg = CyclingDataMsg.decode(m.data)
            val ts = msg.files.firstOrNull()?.timestamp
            if (ts != null && ts != file.timestamp) continue // Block einer anderen Datei
            msg.fileContent?.let { buffer.write(it) }
            if (!mergedSend) confirm(status = 0) // Generation 4: die Original-App bestätigt nur Fehler
            onProgress(buffer.size().toLong(), expected ?: 0L)
        }
        return buffer.toByteArray()
    }

    /**
     * Startzeit aus dem Zeitstempel der Fahrtenliste. Ob Unix- oder FIT-Zeit geliefert wird, ist noch nicht geklärt;
     * Werte über 1,5 Mrd. sind Unix-Sekunden (ab 2017), kleinere FIT-Sekunden (seit 1989-12-31).
     */
    internal fun fileStart(ts: Long): LocalDateTime {
        val unix = if (ts > UNIX_THRESHOLD) ts else ts + FitParser.FIT_EPOCH_OFFSET_SECONDS
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(unix), zone)
    }

    private companion object {
        const val UNIX_THRESHOLD = 1_500_000_000L
    }
}

private const val LOCAL_MEMBER_ID = "ridevault-local"
