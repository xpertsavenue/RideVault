package io.github.xpertsavenue.ridevault.driver.igpsport

import io.github.xpertsavenue.ridevault.device.DeviceModel
import io.github.xpertsavenue.ridevault.device.DeviceRef
import io.github.xpertsavenue.ridevault.device.GattChannel
import io.github.xpertsavenue.ridevault.device.ScanInfo
import io.github.xpertsavenue.ridevault.device.SyncEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimaler FIT-Schreiber für Tests (nur record-Nachrichten). */
private fun fitFile(startFit: Long, n: Int): ByteArray {
    fun le(v: Long, size: Int) = ByteArray(size) { ((v shr (8 * it)) and 0xFF).toByte() }
    val body = ByteArrayOutputStream()
    body.write(0x40); body.write(0); body.write(0); body.write(le(20, 2))
    val fields = listOf(Triple(253, 4, 0x86), Triple(0, 4, 0x85), Triple(1, 4, 0x85), Triple(5, 4, 0x86))
    body.write(fields.size)
    fields.forEach { (a, b, c) -> body.write(a); body.write(b); body.write(c) }
    val semi = { d: Double -> (d * 2147483648.0 / 180.0).toLong() }
    repeat(n) { i ->
        body.write(0)
        body.write(le(startFit + 5L * i, 4)); body.write(le(semi(52.52 + i * 0.0001), 4)); body.write(le(semi(13.40), 4))
        body.write(le(1000L * (i + 1), 4))
    }
    val data = body.toByteArray()
    val out = ByteArrayOutputStream()
    out.write(14); out.write(0x20); out.write(le(2100, 2)); out.write(le(data.size.toLong(), 4))
    out.write(".FIT".toByteArray()); out.write(le(0, 2)); out.write(data); out.write(le(0, 2))
    return out.toByteArray()
}

/** Fake-Gerät: antwortet auf die Schreibzugriffe wie ein iGPSPORT. */
private class FakeDevice(private val files: Map<Long, ByteArray>, private val blockSize: Int = 40) : GattChannel {
    val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    val control = ArrayList<ByteArray>()
    val operations = ArrayList<Int>()
    private var pendingHead: Head20? = null
    var corruptFirstBlock = false
    var respondToList = true

    override val notifications get() = flow
    override suspend fun writeControl(data: ByteArray) { control += data }

    override suspend fun write(data: ByteArray) {
        val head = pendingHead
        if (head == null) {
            pendingHead = Head20.parse(data)
            return
        }
        pendingHead = null
        if (head.service != Service.CYCLING_DATA) return // z. B. der Anmelde-Handshake: dieses Fake-Gerät schweigt dazu
        operations += head.operation
        val msg = CyclingDataMsg.decode(data)
        when (head.operation) {
            CyclingOp.LIST_GET -> if (respondToList) {
                send(CyclingOp.LIST_SEND, CyclingDataMsg(CyclingOp.LIST_SEND, files = files.map { (ts, b) -> FileFlag(ts, b.size.toLong()) }))
            }
            CyclingOp.FILE_GET -> {
                val ts = msg.files.single().timestamp
                val bytes = files.getValue(ts)
                bytes.toList().chunked(blockSize).forEachIndexed { i, block ->
                    val m = CyclingDataMsg(CyclingOp.FILE_SEND, files = listOf(FileFlag(ts)), fileContent = block.toByteArray())
                    send(CyclingOp.FILE_SEND, m, corruptCrc = corruptFirstBlock && i == 0)
                }
            }
        }
    }

    private suspend fun send(op: Int, msg: CyclingDataMsg, corruptCrc: Boolean = false) {
        val data = msg.encode()
        val head = Head20.build(Service.CYCLING_DATA, op, data)
        if (corruptCrc) { head[9] = (head[9] + 1).toByte(); head[19] = Crc8.calc(head, 0, 19).toByte() }
        (head + data).toList().chunked(20).forEach { flow.emit(it.toByteArray()) }
    }
}

class SessionTest {
    private val ref = DeviceRef("AA:BB", "iGS320", DeviceModel("iGPSPORT", "iGS320"))
    private val ts = 1_150_000_000L // FIT-Sekunden (< 1,5 Mrd: wird als FIT-Zeit gelesen)

    private fun session(dev: FakeDevice) = IgpsportSession(ref, dev, ZoneOffset.UTC, listTimeoutMs = 300, listIdleMs = 50, chunkTimeoutMs = 300, handshakeTimeoutMs = 60)

    @Test
    fun `Treiber erkennt iGPSPORT-Namen`() {
        val d = IgpsportDriver()
        assertEquals("iGS320", d.identify(ScanInfo("iGS320", "x"))?.name)
        assertEquals("iGS520", d.identify(ScanInfo("iGS520_U", "x"))?.name)
        assertEquals("BSC200", d.identify(ScanInfo("BSC200", "x"))?.name)
        assertNull(d.identify(ScanInfo("BKV500-1A2B", "x")))
        assertNull(d.identify(ScanInfo(null, "x")))
        assertTrue(d.gattProfile.serviceContains.endsWith("ca9e") && d.gattProfile.controlServiceContains!!.endsWith("ca8e"))
    }

    @Test
    fun `komplette Synchronisation mit mehreren Bloecken und Bestaetigungen`() = runBlocking {
        val fit = fitFile(ts, 8)
        val dev = FakeDevice(mapOf(ts to fit), blockSize = 40)
        val events = session(dev).syncRides { false }.toList()

        assertEquals(SyncEvent.Started(1), events.first())
        assertEquals(SyncEvent.Finished, events.last())
        val ride = events.filterIsInstance<SyncEvent.RideReceived>().single().ride
        assertEquals(8, ride.track.size)
        assertEquals(8, ride.samples.size)
        assertEquals(52.52, ride.track.first().latitude, 1e-5)
        assertEquals(0.08, ride.samples.last().distanceKm!!, 1e-9) // 8000 * 0,01 m = 80 m
        // jeder Block wurde bestätigt: Status 0 über den Steuerkanal
        val blocks = (fit.size + 39) / 40
        assertEquals(blocks, dev.control.size)
        assertTrue(dev.control.all { Head20.parse(it)!!.bytes[7].toInt() == 0 && Head20.parse(it)!!.type == 2 })
        assertEquals(listOf(CyclingOp.LIST_GET, CyclingOp.FILE_GET), dev.operations)
    }

    @Test
    fun `bekannte Fahrt wird uebersprungen und nicht geladen`() = runBlocking {
        val dev = FakeDevice(mapOf(ts to fitFile(ts, 3)))
        val events = session(dev).syncRides { true }.toList()
        assertEquals(1, events.filterIsInstance<SyncEvent.RideSkipped>().size)
        assertEquals(listOf(CyclingOp.LIST_GET), dev.operations)
        assertTrue(dev.control.isEmpty())
    }

    @Test
    fun `fehlerhafter Block wird mit Status 1 abgelehnt`() = runBlocking {
        val dev = FakeDevice(mapOf(ts to fitFile(ts, 3)), blockSize = 1000).apply { corruptFirstBlock = true }
        // Das Fake-Gerät sendet den fehlerhaften Block nicht neu: die Sitzung muss ihn ablehnen und dann in den Timeout laufen
        assertFailsWith<IgpsportProtocolException> { session(dev).syncRides { false }.toList() }
        assertEquals(1, dev.control.size)
        assertEquals(1, Head20.parse(dev.control[0])!!.bytes[7].toInt())
    }

    @Test
    fun `keine Antwort auf die Liste ist ein Fehler`() = runBlocking {
        val dev = FakeDevice(emptyMap()).apply { respondToList = false }
        assertFailsWith<IgpsportProtocolException> { session(dev).syncRides { false }.toList() }
        Unit
    }

    @Test
    fun `Startzeit stammt aus der Liste, Unix- und FIT-Zeit werden erkannt`() {
        val s = session(FakeDevice(emptyMap()))
        val unix = 1_780_000_000L
        // dieselbe Zeit als FIT-Sekunden (seit 1989-12-31) angegeben ergibt dieselbe Startzeit
        assertEquals(s.fileStart(unix), s.fileStart(unix - 631_065_600L))
        assertEquals(java.time.LocalDateTime.of(2026, 5, 28, 20, 26, 40), s.fileStart(unix))
    }
}


/** Fake-Gerät mit getrenntem Daten- und Steuerkanal (wie ein echter iGPSPORT): Befehle = Daten zuerst, dann Kopf auf dem Steuerkanal. */
private class SplitFakeDevice(
    private val files: Map<Long, ByteArray>,
    private val blockSize: Int = 40,
    private val headerFirst: Boolean = false,
    /** Antwort auf BOND_INFO: null = schweigen, sonst (status, memberId). */
    private val bond: Pair<Long, String>? = null,
) : GattChannel {
    val dataFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    val controlFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    val log = ArrayList<String>()
    val confirms = ArrayList<Head20>()
    val bleMessages = ArrayList<BleMsg>()
    private var pendingData = ByteArray(0)

    override val hasControlChannel get() = true
    override val notifications get() = dataFlow
    override val controlNotifications get() = controlFlow
    override suspend fun readValue(characteristicUuid: String): ByteArray? =
        if (characteristicUuid == "00002a28-0000-1000-8000-00805f9b34fb") "V1.2.3 ".toByteArray() else null

    override suspend fun write(data: ByteArray) {
        log += "data"
        pendingData += data
    }

    override suspend fun writeControl(data: ByteArray) {
        val head = Head20.parse(data)!!
        if (head.type == Head20.TYPE_CONFIRM) { log += "confirm"; confirms += head; return }
        log += "head"
        val payload = pendingData
        pendingData = ByteArray(0)
        if (head.service == Service.BLE) {
            val ble = BleMsg.decode(payload)
            bleMessages += ble
            if (head.operation == BleOp.BOND_INFO && bond != null) {
                replyBle(BleMsg(BleOp.BOND_INFO, listOf(BleData(bond.first, bond.second))))
            } else if (head.operation == BleOp.CONNECT_STATUS) {
                replyBle(BleMsg(BleOp.CONNECT_STATUS, listOf(BleData(1, null))))
            }
            return
        }
        val msg = CyclingDataMsg.decode(payload)
        when (head.operation) {
            CyclingOp.LIST_GET -> reply(CyclingOp.LIST_SEND, CyclingDataMsg(CyclingOp.LIST_SEND, files = files.map { (ts, b) -> FileFlag(ts, b.size.toLong()) }))
            CyclingOp.FILE_GET -> {
                val ts = msg.files.single().timestamp
                files.getValue(ts).toList().chunked(blockSize).forEach { block ->
                    reply(CyclingOp.FILE_SEND, CyclingDataMsg(CyclingOp.FILE_SEND, files = listOf(FileFlag(ts)), fileContent = block.toByteArray()))
                }
            }
        }
    }

    private suspend fun replyBle(msg: BleMsg) {
        val data = msg.encode()
        data.toList().chunked(20).forEach { dataFlow.emit(it.toByteArray()) }
        controlFlow.emit(Head20.build(Service.BLE, msg.operation, data, type = Head20.TYPE_TRANSMIT_COMPLETE))
    }

    /** Nutzlast in 20-Byte-Benachrichtigungen auf dem Datenkanal, der Kopf (Typ 3) auf dem Steuerkanal. */
    private suspend fun reply(op: Int, msg: CyclingDataMsg) {
        val data = msg.encode()
        val head = Head20.build(Service.CYCLING_DATA, op, data, type = Head20.TYPE_TRANSMIT_COMPLETE)
        if (headerFirst) controlFlow.emit(head)
        data.toList().chunked(20).forEach { dataFlow.emit(it.toByteArray()) }
        if (!headerFirst) controlFlow.emit(head)
    }
}

class SplitSessionTest {
    private val ref = DeviceRef("AA:BB", "BSC200", DeviceModel("iGPSPORT", "BSC200"))
    private val ts = 1_150_000_000L

    private fun session(dev: SplitFakeDevice) =
        IgpsportSession(ref, dev, ZoneOffset.UTC, listTimeoutMs = 300, listIdleMs = 50, chunkTimeoutMs = 300, handshakeTimeoutMs = 60, mergedSend = false)

    @Test
    fun `Befehl geht erst als Daten auf den Datenkanal, dann als Kopf auf den Steuerkanal`() = runBlocking {
        val dev = SplitFakeDevice(mapOf(ts to fitFile(ts, 3)))
        session(dev).syncRides { true }.toList()
        // Handshake (BOND_INFO, CONNECT_STATUS) und LIST_GET, je data dann head – danach nichts mehr (die Fahrt ist "bekannt" und wird nicht geladen)
        assertEquals(listOf("data", "head", "data", "head", "data", "head"), dev.log)
    }

    @Test
    fun `komplette Synchronisation im getrennten Modus, Daten vor Kopf`() = runBlocking {
        val fit = fitFile(ts, 8)
        val dev = SplitFakeDevice(mapOf(ts to fit), blockSize = 40)
        val events = session(dev).syncRides { false }.toList()
        val ride = events.filterIsInstance<SyncEvent.RideReceived>().single().ride
        assertEquals(8, ride.track.size)
        assertEquals(0.08, ride.samples.last().distanceKm!!, 1e-9)
        assertEquals((fit.size + 39) / 40, dev.confirms.size) // jeder Block bestätigt
        assertTrue(dev.confirms.all { it.bytes[7].toInt() == 0 })
        assertEquals(SyncEvent.Finished, events.last())
    }

    @Test
    fun `komplette Synchronisation im getrennten Modus, Kopf vor Daten`() = runBlocking {
        val dev = SplitFakeDevice(mapOf(ts to fitFile(ts, 5)), blockSize = 1000, headerFirst = true)
        val events = session(dev).syncRides { false }.toList()
        assertEquals(5, events.filterIsInstance<SyncEvent.RideReceived>().single().ride.track.size)
    }

    @Test
    fun `Handshake meldet bei gebundenem Geraet dieselbe Mitglieds-ID zurueck`() = runBlocking {
        val dev = SplitFakeDevice(mapOf(ts to fitFile(ts, 3)), bond = 1L to "member-42")
        session(dev).syncRides { false }.toList()
        assertEquals(listOf(BleOp.BOND_INFO, BleOp.CONNECT_STATUS), dev.bleMessages.map { it.operation })
        val status = dev.bleMessages[1].data.single()
        assertEquals(1L, status.status)
        assertEquals("member-42", status.memberId)
    }

    @Test
    fun `ungebundenes Geraet bekommt den Verbindungsstatus mit lokaler ID, die Synchronisation laeuft`() = runBlocking {
        val dev = SplitFakeDevice(mapOf(ts to fitFile(ts, 3)), bond = 0L to "")
        val events = session(dev).syncRides { false }.toList()
        assertEquals(listOf(BleOp.BOND_INFO, BleOp.CONNECT_STATUS), dev.bleMessages.map { it.operation }) // nie BOND_REQ
        assertEquals(1, events.filterIsInstance<SyncEvent.RideReceived>().size)
    }

    @Test
    fun `Handshake ohne Antwort haelt die Synchronisation nicht auf`() = runBlocking {
        val dev = SplitFakeDevice(mapOf(ts to fitFile(ts, 3)), bond = null)
        val events = session(dev).syncRides { false }.toList()
        assertEquals(1, events.filterIsInstance<SyncEvent.RideReceived>().size)
    }

    @Test
    fun `Firmware kommt aus der Geraeteinformation`() = runBlocking {
        val info = session(SplitFakeDevice(emptyMap())).deviceInfo()
        assertEquals("V1.2.3", info.firmware)
        assertNull(info.batteryPercent)
    }

    @Test
    fun `im Strommodus gehen Kopf und Daten zusammen auf den Hauptkanal`() = runBlocking {
        val writes = ArrayList<ByteArray>()
        val controlWrites = ArrayList<ByteArray>()
        val quiet = object : GattChannel {
            override val hasControlChannel get() = true
            override val notifications get() = MutableSharedFlow<ByteArray>()
            override val controlNotifications get() = MutableSharedFlow<ByteArray>()
            override suspend fun write(data: ByteArray) { writes += data }
            override suspend fun writeControl(data: ByteArray) { controlWrites += data }
        }
        val s = IgpsportSession(ref, quiet, ZoneOffset.UTC, listTimeoutMs = 100, listIdleMs = 20, chunkTimeoutMs = 100, handshakeTimeoutMs = 20, mergedSend = true)
        runCatching { s.syncRides { false }.toList() }
        assertTrue(writes.isNotEmpty())
        assertEquals(Head20.TYPE_COMMAND, writes.first()[0].toInt())
        assertEquals(Head20.SIZE + 4, writes.first().size) // Kopf (20) + BOND_INFO-Daten (4)
        assertTrue(controlWrites.isEmpty())
    }
}

class RawFitStreamTest {
    @Test
    fun `roher FIT-Strom nach dem Kopf wird bis zur FIT-Laenge gesammelt`() {
        val fit = fitFile(1_000_000L, 40)
        // Kopf wie am BSC200 beobachtet: FILE_SEND, Endetyp 3, Länge nennt nur das erste Stück; danach 7 Byte Vorspann + FIT
        val firstLen = 100
        val head = Head20.build(Service.CYCLING_DATA, CyclingOp.FILE_SEND, ByteArray(firstLen), fileTag = 0x55)
        head[10] = RawFitStream.END_RAW.toByte()
        // Kopf-CRC neu berechnen
        head[19] = Crc8.calc(head, 0, 19).toByte()
        val stream = head + byteArrayOf(0, 0, 0, 3, 8, 0x61, 0x54) + fit
        val raw = RawFitStream()
        assertTrue(raw.isStartHeader(stream.copyOf(20)))
        var result: ByteArray? = null
        val chunks = stream.toList().chunked(60).map { it.toByteArray() }
        chunks.forEachIndexed { i, c ->
            val r = if (i == 0) raw.start(c) else raw.feed(c)
            if (r != null) result = r
        }
        assertEquals(fit.toList(), result!!.toList())
        assertTrue(!raw.active)
    }
}
