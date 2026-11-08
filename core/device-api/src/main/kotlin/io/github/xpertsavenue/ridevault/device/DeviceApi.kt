package io.github.xpertsavenue.ridevault.device

import io.github.xpertsavenue.ridevault.model.Ride
import io.github.xpertsavenue.ridevault.model.RideKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDateTime

/** Ergebnis eines BLE-Scans, so wie ihn die BLE-Schicht an die Treiber weiterreicht. */
data class ScanInfo(val name: String?, val address: String, val serviceUuids: List<String> = emptyList())

data class DeviceModel(val vendor: String, val name: String)

data class DeviceRef(val address: String, val name: String, val model: DeviceModel)

/**
 * Wie die BLE-Schicht Service und Characteristics eines Treibers findet.
 * UUIDs werden per Teilstring verglichen (normalisiert, ohne Bindestriche, klein).
 */
data class GattProfile(
    val serviceContains: String,
    val notifyContains: String,
    val writeContains: String,
    val preferredMtu: Int = 247,
    /** Optionaler zweiter Kanal (z. B. Steuer-/Bestätigungskanal). Alle drei Angaben gehören zusammen. */
    val controlServiceContains: String? = null,
    val controlNotifyContains: String? = null,
    val controlWriteContains: String? = null,
    /** Weitere Notify-Characteristics, deren Benachrichtigungen aktiviert werden sollen (manche Geräte erwarten alle Kanäle). */
    val extraNotifyContains: List<String> = emptyList(),
    /** Das Gerät verlangt eine Bluetooth-Kopplung (Bonding), bevor es Befehle annimmt (z. B. iGPSPORT im "Pair Phone"-Modus). */
    val requireBonding: Boolean = false,
    /** Schreib-Characteristic des Zusatzkanals (z. B. 3. UART beim iGPSPORT), auf den [GattChannel.writeExtra] schreibt. */
    val extraWriteContains: String? = null,
    /** Schreib-Characteristic des vierten Kanals (iGPSPORT: Strecken und Dateien), auf den [GattChannel.writeFourth] schreibt. */
    val fourthWriteContains: String? = null,
)

/** Schmale Sicht des Treibers auf die Verbindung; die echte Implementierung liefert die BLE-Schicht. */
interface GattChannel {
    /** Heißer Strom (z. B. SharedFlow mit Puffer): Abonnieren darf keine Benachrichtigung verpassen. */
    val notifications: Flow<ByteArray>
    suspend fun write(data: ByteArray)

    /** Benachrichtigungen des optionalen Steuerkanals (leer, wenn das Profil keinen hat). */
    val controlNotifications: Flow<ByteArray> get() = emptyFlow()

    /** Benachrichtigungen der Zusatzkanäle ([GattProfile.extraNotifyContains]), z. B. der 3. UART beim iGPSPORT. */
    val extraNotifications: Flow<ByteArray> get() = emptyFlow()

    /** Schreibt auf den Steuerkanal; ohne Steuerkanal auf den Hauptkanal. */
    suspend fun writeControl(data: ByteArray) = write(data)

    /** Schreibt auf den Zusatzkanal ([GattProfile.extraWriteContains]); ohne ihn auf den Hauptkanal. */
    suspend fun writeExtra(data: ByteArray) = write(data)

    /** Schreibt auf den vierten Kanal ([GattProfile.fourthWriteContains]); ohne ihn auf den Zusatzkanal. */
    suspend fun writeFourth(data: ByteArray, onProgress: (Int) -> Unit = {}) {
        writeExtra(data)
        onProgress(100)
    }

    /** true, wenn das Gerät den im Profil beschriebenen Steuerkanal wirklich hat. */
    val hasControlChannel: Boolean get() = false

    /**
     * Liest eine Characteristic (volle UUID, klein geschrieben) aus beliebigem Dienst, z. B. Softwareversion
     * `00002a28-0000-1000-8000-00805f9b34fb`. Null, wenn es sie nicht gibt oder das Lesen scheitert.
     */
    suspend fun readValue(characteristicUuid: String): ByteArray? = null
}

enum class Capability {
    READ_BATTERY, READ_FIRMWARE, SYNC_RIDES,
    SET_TIME, SET_TIME_ZONE, SET_UNITS, SET_LANGUAGE,
    SET_BACKLIGHT, SET_TIMED_BACKLIGHT, SET_AUTO_STANDBY, SET_USER_PROFILE,
}

data class DeviceInfo(
    val batteryPercent: Int?,
    val firmware: String?,
    val firmwareDate: String?,
    val hardware: String? = null,
    val manufacturer: String? = null,
    val serial: String? = null,
)

enum class SpeedUnit { KMH, MPH }

sealed interface Backlight {
    data object Off : Backlight
    data object AlwaysOn : Backlight
    data class Timeout(val seconds: Int) : Backlight
}

sealed interface DeviceSetting {
    /** Uhrzeit setzen. [timeFormat] ist der rohe Gerätewert (Bedeutung noch nicht verifiziert). */
    data class Time(val local: LocalDateTime, val utc: LocalDateTime, val timeFormat: Int = 0) : DeviceSetting
    data class TimeZone(val hours: Int, val east: Boolean) : DeviceSetting
    data class Units(val speed: SpeedUnit) : DeviceSetting

    /** [index] 1-basiert in der Reihenfolge des Geräts (siehe Treiber-Doku). */
    data class Language(val index: Int) : DeviceSetting
    data class BacklightMode(val backlight: Backlight) : DeviceSetting

    /** Minuten seit Mitternacht; beide null schaltet die Zeitsteuerung aus. */
    data class TimedBacklight(val startMinutes: Int?, val endMinutes: Int?) : DeviceSetting
    data class AutoStandby(val hours: Int, val minutes: Int) : DeviceSetting
    data class UserProfile(val male: Boolean, val heightCm: Int, val weightKg: Int, val age: Int) : DeviceSetting
}

sealed interface SyncEvent {
    data class Started(val rideCount: Int) : SyncEvent
    data class Progress(val received: Long, val total: Long) : SyncEvent

    /** Eine Fahrt wird jetzt geladen: Nummer unter den zu ladenden Fahrten (ab 1), deren Anzahl und die Startzeit der Fahrt. */
    data class Loading(val index: Int, val total: Int, val startTime: LocalDateTime) : SyncEvent

    /** Der Konsument speichert die Fahrt; erst danach bestätigt der Treiber sie dem Gerät. */
    data class RideReceived(val ride: Ride) : SyncEvent
    data class RideSkipped(val startTime: LocalDateTime) : SyncEvent
    data object Finished : SyncEvent
}

/** Art einer Geräteeinstellung. Alle Werte sind ganze Zahlen (Schalter 0/1, Auswahl = Wert aus der Liste, Zahl = Wert). */
sealed interface ConfigKind {
    data object Toggle : ConfigKind
    data class Choice(val options: List<Int>) : ConfigKind
    data class Number(val min: Int, val max: Int, val step: Int = 1) : ConfigKind
}

/** Eine Einstellung, die das Gerät kennen kann. [group] gruppiert sie in der Oberfläche. */
data class ConfigItem(val id: String, val group: String, val kind: ConfigKind)

/** Gelesene Werte. Einstellungen, die das Gerät nicht meldet, fehlen in [values]. [choices] ersetzt ggf. die Auswahlliste (z. B. Sprachen). */
data class ConfigState(val values: Map<String, Int>, val choices: Map<String, List<Int>> = emptyMap())

/** Geräte, die ihre Einstellungen lesen und ändern lassen (z. B. iGPSPORT). */
interface ConfigurableSession {
    val configItems: List<ConfigItem>
    suspend fun readConfig(): ConfigState
    suspend fun writeConfig(id: String, value: Int)
}

/**
 * Eine Seite des Fahrradcomputers (iGPSPORT, config.proto `page_msg`).
 * [data] sind Datenfeld-Nummern von links nach rechts und oben nach unten; [layout] hält je Zeile die Zahl der Felder (3 Bit pro Zeile,
 * Zeile 1 in den niedrigsten Bits), [lineWidth] je Zeile die Höhe (ebenfalls 3 Bit pro Zeile).
 */
data class DevicePage(
    val index: Int,
    val enabled: Boolean,
    val type: Int,
    val data: List<Int>,
    val layout: Int,
    val lineWidth: Int,
    val name: String? = null,
    val dataSite: Int? = null,
    val mainPage: Boolean = false,
    val graphicDisplay: Int? = null,
)

/** Was das Gerät für seine Seiten unterstützt. */
data class PageCapabilities(
    val maxRows: Int?,
    val maxPerRow: Int?,
    val maxDataPages: Int?,
    /** Datenfeld-Nummern, die das Gerät nicht anzeigen kann. */
    val unsupportedData: Set<Int>,
    /** Seitenlayouts, die das Gerät kennt (Bitmaske wie [DevicePage.layout]); leer = unbekannt. */
    val layouts: List<Int>,
)

/** Fahrmodus des Geräts (z. B. Straße, Pendeln, Tour); jeder hat eigene Seiten. */
data class RidingMode(val index: Int, val name: String?, val inUse: Boolean, val valid: Boolean)

data class DevicePages(val pages: List<DevicePage>, val capabilities: PageCapabilities)

/** Geräte, deren Seiten (Datenfelder und Anordnung) sich lesen und ändern lassen. */
interface PageConfigurableSession {
    suspend fun readRidingModes(): List<RidingMode>
    suspend fun readPages(mode: Int?): DevicePages
    suspend fun writePages(mode: Int?, pages: List<DevicePage>)
}

/** Eine Strecke (Navigationsdatei) auf dem Gerät. */
data class DeviceRoute(
    val id: Long,
    val name: String?,
    /** Dateityp laut Gerät (1 = CNX, 2 = GPX, 3 = FIT, 4 = TCX, 5 = XML). */
    val fileType: Int?,
    val distanceM: Long?,
    val startLat: Double?,
    val startLon: Double?,
    /** true = wird gerade zur Navigation benutzt. */
    val inUse: Boolean?,
)

/** Geräte, auf die sich Strecken zur Navigation laden lassen. */
interface RouteConfigurableSession {
    suspend fun readRoutes(): List<DeviceRoute>

    /** Sendet eine Strecke (Dateiendung bestimmt das Format, z. B. "gpx"). Wirft eine Ausnahme, wenn das Gerät sie ablehnt. */
    suspend fun sendRoute(name: String, extension: String, content: ByteArray, onProgress: (Int) -> Unit = {})
    suspend fun deleteRoute(route: DeviceRoute)

    /** Macht die Strecke zur aktiven (benutzten) Strecke des Geräts. */
    suspend fun useRoute(route: DeviceRoute)

    /** Benennt eine Strecke auf dem Gerät um. */
    suspend fun renameRoute(route: DeviceRoute, newName: String)
}

enum class RideState { IDLE, RIDING, PAUSED }

/** Livewerte des Geräts in gewohnten Einheiten. Fehlende Werte meldet das Gerät nicht. */
data class LiveData(
    val rideState: RideState? = null,
    val navigating: Boolean? = null,
    val speedKmh: Double? = null, val avgSpeedKmh: Double? = null, val maxSpeedKmh: Double? = null,
    val timeSec: Long? = null, val distanceKm: Double? = null,
    val cadence: Int? = null, val avgCadence: Int? = null, val maxCadence: Int? = null,
    val heartRate: Int? = null, val avgHeartRate: Int? = null, val maxHeartRate: Int? = null,
    val power: Int? = null, val avgPower: Int? = null, val maxPower: Int? = null,
    val altitudeM: Double? = null, val slopePercent: Double? = null, val ascentM: Int? = null,
    val latitude: Double? = null, val longitude: Double? = null,
)

/** Zustand des Geräts selbst (Akku, Speicher) – ohne Fahrdaten. */
data class DeviceHealth(
    val batteryPercent: Int? = null,
    val batteryMillivolt: Int? = null,
    /** Freier Speicher in Prozent. */
    val storageFreePercent: Int? = null,
    val rideState: RideState? = null,
    val navigating: Boolean? = null,
)

/** Geräte, die Livewerte und ihren Zustand melden. */
interface LiveSession {
    /** Null, wenn das Gerät nicht antwortet. */
    suspend fun readLive(): LiveData?
    suspend fun readHealth(): DeviceHealth
}

interface BikeComputerSession {
    val capabilities: Set<Capability>
    suspend fun deviceInfo(): DeviceInfo
    fun syncRides(isKnown: suspend (RideKey) -> Boolean): Flow<SyncEvent>
    suspend fun apply(setting: DeviceSetting)
}

interface BikeComputerDriver {
    val id: String
    val displayName: String
    val gattProfile: GattProfile

    /** Erkennt, ob der Scan-Treffer zu diesem Hersteller gehört. */
    fun identify(scan: ScanInfo): DeviceModel?
    fun open(device: DeviceRef, channel: GattChannel): BikeComputerSession
}
