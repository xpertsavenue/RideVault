package io.github.xpertsavenue.ridevault.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.github.xpertsavenue.ridevault.device.GattChannel
import io.github.xpertsavenue.ridevault.device.GattProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Aktive GATT-Verbindung zu einem Fahrradcomputer. Findet Service und Characteristics
 * anhand des [GattProfile] des Treibers, schaltet Benachrichtigungen ein und serialisiert Schreibzugriffe.
 */
@SuppressLint("MissingPermission")
class BleGattConnection private constructor(private val profile: GattProfile) : GattChannel {

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val notifications: SharedFlow<ByteArray> get() = _notifications

    private val _controlNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val controlNotifications: SharedFlow<ByteArray> get() = _controlNotifications

    private val _extraNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val extraNotifications: SharedFlow<ByteArray> get() = _extraNotifications

    private val _connected = MutableStateFlow(true)

    /** false, sobald die Verbindung verloren ging oder geschlossen wurde. */
    val connected: StateFlow<Boolean> get() = _connected

    private val ready = CompletableDeferred<Unit>()
    private val writeLock = Mutex()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var controlWriteChar: BluetoothGattCharacteristic? = null
    private var controlNotifyChar: BluetoothGattCharacteristic? = null
    private var extraWriteChar: BluetoothGattCharacteristic? = null
    private var fourthWriteChar: BluetoothGattCharacteristic? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var pendingWrite: CompletableDeferred<Int>? = null

    /** Ausgehandelte MTU (Standard 23); ein Schreibzugriff darf höchstens MTU-3 Byte Nutzdaten haben. */
    @Volatile private var mtu = 23
    private var allCharacteristics: Map<String, BluetoothGattCharacteristic> = emptyMap()
    private val readLock = Mutex()
    private var pendingRead: CompletableDeferred<ByteArray?>? = null

    override val hasControlChannel: Boolean get() = controlNotifyChar != null && controlWriteChar != null

    /** Schreibt auf den Hauptkanal; Daten über MTU-3 Byte werden in mehrere Schreibzugriffe aufgeteilt (wie `split()` der Original-App). */
    override suspend fun write(data: ByteArray) {
        val max = (mtu - 3).coerceAtLeast(20)
        if (data.size <= max) {
            writeTo(writeChar, data, "")
        } else {
            data.toList().chunked(max).forEach { writeTo(writeChar, it.toByteArray(), "") }
        }
    }

    override suspend fun readValue(characteristicUuid: String): ByteArray? {
        val g = gatt ?: return null
        val ch = allCharacteristics[characteristicUuid.lowercase()] ?: return null
        return readLock.withLock {
            val result = CompletableDeferred<ByteArray?>().also { pendingRead = it }
            if (!g.readCharacteristic(ch)) {
                pendingRead = null
                return@withLock null
            }
            try {
                withTimeout(READ_TIMEOUT_MS) { result.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                null
            } finally {
                pendingRead = null
            }
        }
    }

    /** Schreibt auf den Steuerkanal; hat das Gerät keinen, auf den Hauptkanal. */
    override suspend fun writeFourth(data: ByteArray, onProgress: (Int) -> Unit) =
        writeChunked(fourthWriteChar ?: extraWriteChar ?: writeChar, data, if (fourthWriteChar != null) "FOURTH " else "", onProgress)

    override suspend fun writeExtra(data: ByteArray) = writeChunked(extraWriteChar ?: writeChar, data, if (extraWriteChar != null) "EXTRA " else "")

    override suspend fun writeControl(data: ByteArray) = writeChunked(controlWriteChar ?: writeChar, data, if (controlWriteChar != null) "CTRL " else "")

    /**
     * Schreibt [data] in Stücken von höchstens MTU-3 Byte. Ein größerer einzelner Schreibvorgang wird vom Bluetooth-System abgelehnt
     * oder gekürzt; Dateien (Strecken) sind größer als eine MTU. Das Protokoll zeigt je Stück eine Zeile (nur der Anfang).
     */
    private suspend fun writeChunked(ch: BluetoothGattCharacteristic?, data: ByteArray, label: String, onProgress: (Int) -> Unit = {}) {
        val max = (mtu - 3).coerceAtLeast(20)
        if (data.size <= max) {
            writeTo(ch, data, label)
            onProgress(100)
            return
        }
        var offset = 0
        var lastPercent = -1
        while (offset < data.size) {
            val end = minOf(offset + max, data.size)
            writeTo(ch, data.copyOfRange(offset, end), label, quiet = offset > 0)
            offset = end
            val percent = (offset * 100L / data.size).toInt()
            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
        }
    }

    private suspend fun writeTo(ch: BluetoothGattCharacteristic?, data: ByteArray, label: String, quiet: Boolean = false) {
        val g = gatt ?: throw BleException("Not connected")
        if (ch == null) throw BleException("Write characteristic missing")
        writeLock.withLock {
            if (!_connected.value) throw BleException("Disconnected")
            val withResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val type = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            if (!quiet) Log.d(TAG, "${label}TX ${data.hex()}")
            val done = CompletableDeferred<Int>().also { pendingWrite = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = data
                    g.writeCharacteristic(ch)
                }
            }
            if (!started) {
                pendingWrite = null
                throw BleException("Write rejected")
            }
            try {
                val status = withTimeout(WRITE_TIMEOUT_MS) { done.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("Write failed (status $status)")
            } finally {
                pendingWrite = null
            }
            if (!withResponse) delay(NO_RESPONSE_GAP_MS)
        }
    }

    fun close() {
        _connected.value = false
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    /** Aktiviert die Benachrichtigungen der Reihe nach (Android erlaubt nur einen Descriptor-Schreibvorgang gleichzeitig). */
    private fun enableNextNotification(g: BluetoothGatt) {
        val ch = notifyQueue.removeFirstOrNull()
        if (ch == null) {
            ready.complete(Unit)
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) {
            enableNextNotification(g)
            return
        }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        if (!ok) ready.completeExceptionally(BleException("Could not enable notifications"))
    }

    private fun routeNotification(c: BluetoothGattCharacteristic, value: ByteArray) {
        val control = controlNotifyChar?.uuid == c.uuid
        val main = c.uuid.matches(profile.notifyContains)
        if (!control && !main) {
            Log.d(TAG, "OTHER(${c.uuid}) RX ${value.hex()}")
            _extraNotifications.tryEmit(value.copyOf())
            return
        }
        Log.d(TAG, (if (control) "CTRL " else "") + "RX ${value.hex()}")
        (if (control) _controlNotifications else _notifications).tryEmit(value.copyOf())
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    if (!g.requestMtu(profile.preferredMtu)) g.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS -> {
                    Log.w(TAG, "Verbindung getrennt, status=$status newState=$newState")
                    _connected.value = false
                    ready.completeExceptionally(BleException("Disconnected (status $status)"))
                    pendingWrite?.complete(-1)
                    runCatching { g.close() }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGattConnection.mtu = mtu
            Log.d(TAG, "MTU=$mtu status=$status")
            g.discoverServices()
        }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null)
        }

        // Android 8–12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Zur Fehlersuche: alle Dienste und Characteristics ins Logcat
            g.services.forEach { svc ->
                Log.d(TAG, "Service ${svc.uuid}: " + svc.characteristics.joinToString { "${it.uuid} (props=0x${Integer.toHexString(it.properties)})" })
            }
            allCharacteristics = g.services.flatMap { it.characteristics }.associateBy { it.uuid.toString().lowercase() }
            val service = g.services.firstOrNull { it.uuid.matches(profile.serviceContains) }
            if (service == null) {
                ready.completeExceptionally(
                    BleException("Service '${profile.serviceContains}' not found: ${g.services.joinToString { it.uuid.toString() }}"),
                )
                return
            }
            val notify = service.characteristics.firstOrNull { it.uuid.matches(profile.notifyContains) }
            val write = service.characteristics.firstOrNull { it.uuid.matches(profile.writeContains) }
            if (notify == null || write == null) {
                ready.completeExceptionally(BleException("Characteristics not found (notify=$notify, write=$write)"))
                return
            }
            writeChar = write
            notifyQueue.add(notify)

            // Optionaler Steuerkanal: fehlt er, arbeitet der Treiber nur mit dem Hauptkanal
            val controlService = profile.controlServiceContains?.let { part -> g.services.firstOrNull { it.uuid.matches(part) } }
            if (controlService != null) {
                controlNotifyChar = profile.controlNotifyContains?.let { part -> controlService.characteristics.firstOrNull { it.uuid.matches(part) } }
                controlWriteChar = profile.controlWriteContains?.let { part -> controlService.characteristics.firstOrNull { it.uuid.matches(part) } }
                controlNotifyChar?.let { notifyQueue.add(it) }
            }
            // Weitere Kanäle (z. B. 3./4. UART beim iGPSPORT): Benachrichtigungen ebenfalls einschalten
            profile.extraNotifyContains.forEach { part ->
                g.services.flatMap { it.characteristics }.firstOrNull { it.uuid.matches(part) }?.let { notifyQueue.add(it) }
            }
            fourthWriteChar = profile.fourthWriteContains?.let { part -> g.services.flatMap { it.characteristics }.firstOrNull { it.uuid.matches(part) } }
            extraWriteChar = profile.extraWriteContains?.let { part -> g.services.flatMap { it.characteristics }.firstOrNull { it.uuid.matches(part) } }
            enableNextNotification(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                enableNextNotification(g)
            } else {
                ready.completeExceptionally(BleException("Enabling notifications failed (status $status)"))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            routeNotification(c, value)
        }

        // Android 8–12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                c.value?.let { routeNotification(c, it) }
            }
        }
    }

    companion object {
        private const val TAG = "RideVaultBle"
        private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 5_000L
        private const val BOND_TIMEOUT_MS = 60_000L
        private const val NO_RESPONSE_GAP_MS = 20L

        /** Verbindet per Adresse und wartet, bis Service gefunden und Benachrichtigungen aktiv sind. */
        suspend fun connect(context: Context, address: String, profile: GattProfile): BleGattConnection {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw BleException("No Bluetooth available")
            val conn = BleGattConnection(profile)
            val device = adapter.getRemoteDevice(address)
            conn.gatt = device.connectGatt(context.applicationContext, false, conn.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { conn.ready.await() }
                if (profile.requireBonding) ensureBonded(context.applicationContext, device)
            } catch (e: Throwable) {
                conn.close()
                throw if (e is BleException) e else BleException("Connect failed: ${e.message}", e)
            }
            return conn
        }

        /**
         * Koppelt das Gerät, falls noch nicht geschehen (wie `requestBonding` der Original-App: `BluetoothDevice.createBond()`).
         * Android zeigt dazu seinen Kopplungsdialog; bei manchen Geräten ist zusätzlich eine Bestätigung am Gerät nötig.
         */
        private suspend fun ensureBonded(context: Context, device: BluetoothDevice) {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            val result = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address != device.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    Log.d(TAG, "Bond state $previous -> $state")
                    when {
                        state == BluetoothDevice.BOND_BONDED -> result.complete(true)
                        state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> result.complete(false)
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            try {
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    Log.d(TAG, "createBond()")
                    if (!device.createBond()) throw BleException("Pairing could not be started")
                }
                val ok = try {
                    withTimeout(BOND_TIMEOUT_MS) { result.await() }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw BleException("Pairing timed out – confirm the pairing on the phone and the bike computer")
                }
                if (!ok) throw BleException("Pairing was rejected")
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        /** Vergleicht normalisiert (ohne Bindestriche, klein) per Teilstring, wie das Original. */
        private fun UUID.matches(part: String) =
            toString().replace("-", "").lowercase().contains(part.lowercase())
    }
}
