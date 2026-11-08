package io.github.xpertsavenue.ridevault.driver.igpsport

import java.io.ByteArrayOutputStream

/** CRC-8/MAXIM (Dallas): Polynom 0x31, gespiegelt 0x8C, Startwert 0 – identisch zur Tabelle `CRC8` im iGPSPORT-App-Code. */
object Crc8 {
    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }
        c
    }

    fun calc(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) crc = table[(crc xor data[i].toInt()) and 0xFF]
        return crc
    }
}

/** Dienstnummern (`service_type_index`) – nur die hier genutzten. */
object Service {
    const val CYCLING_DATA = 6
    const val ROUTE_PLAN = 7
    const val BLE = 10
    const val FACTORY = 11
    const val CONFIG = 12
    const val DEV_STATUS = 13
    const val FILE_OPERATION = 21
}

/** Operationen des Bluetooth-/Bindungsdienstes (`BLE_OPERATE_TYPE`). */
object BleOp {
    const val BOND_INFO = 1
    const val BOND_REQ = 2
    const val CONNECT_STATUS = 3
    const val UNBOND = 4
}

/** Operationen des Fahrten-Dienstes (`CYCLING_DATA_OPERATE_TYPE`). */
object CyclingOp {
    const val LIST_GET = 1
    const val LIST_SEND = 2
    const val FILE_GET = 3
    const val FILE_SEND = 4
    const val FILE_DEL = 5
    const val ALL_DEL = 6
}

/**
 * 20-Byte-Kopf der Generation 2/3 (siehe docs/igpsport-protokoll.md).
 * Aufbau: [0] Typ, [1] Dienst, [2] 2. Dienst, [3] Dateimarke, [4] Operation, [5] 2. Operation, [6] 0xFF,
 * [7..8] Datenlänge (Big-Endian), [9] CRC8(Daten), [10] Endetyp, [11..18] 0xFF, [19] CRC8(0..18).
 */
class Head20(val bytes: ByteArray) {
    init { require(bytes.size == SIZE) { "Kopf muss $SIZE Byte lang sein" } }

    val type: Int get() = u(0)
    val service: Int get() = u(1)
    /** Zweites Dienstbyte (Byte 2): bei Konfiguration die Konfigurationsgruppe. */
    val secondService: Int get() = u(2)
    val fileTag: Int get() = u(3)
    val operation: Int get() = u(4)
    val secondOperation: Int get() = u(5)
    val dataLength: Int get() = (u(7) shl 8) or u(8)
    val dataCrc: Int get() = u(9)
    val endType: Int get() = u(10)
    val totalCrc: Int get() = u(19)
    val headerCrcValid: Boolean get() = Crc8.calc(bytes, 0, 19) == totalCrc

    private fun u(i: Int) = bytes[i].toInt() and 0xFF

    companion object {
        const val SIZE = 20
        const val TYPE_COMMAND = 0x01
        const val TYPE_CONFIRM = 0x02

        /** Kopf, den das Gerät nach einer vollständig gesendeten Nachricht auf dem Steuerkanal schickt ("TX2 received"). */
        const val TYPE_TRANSMIT_COMPLETE = 0x03
        private const val FF = 0xFF.toByte()

        /** Kopf für einen Befehl mit Protobuf-[data]. */
        fun build(service: Int, operation: Int, data: ByteArray, fileTag: Int = 0xFF, type: Int = TYPE_COMMAND, secondService: Int = 0xFF): ByteArray {
            require(data.size <= 0xFFFF) { "Daten zu groß für eine Nachricht: ${data.size}" }
            val b = ByteArray(SIZE) { FF }
            b[0] = type.toByte()
            b[1] = service.toByte()
            b[2] = secondService.toByte()
            b[3] = fileTag.toByte()
            b[4] = operation.toByte()
            b[7] = (data.size shr 8).toByte()
            b[8] = data.size.toByte()
            b[9] = Crc8.calc(data).toByte()
            b[10] = 1 // Endetyp: Protobuf
            b[19] = Crc8.calc(b, 0, 19).toByte()
            return b
        }

        /** Bestätigung (`ConfirmCommand`): Typ 2, [status] 0 = in Ordnung, 1 = Fehler/erneut senden. */
        fun confirm(service: Int, operation: Int, status: Int): ByteArray {
            val b = ByteArray(SIZE) { FF }
            b[0] = TYPE_CONFIRM.toByte()
            b[1] = service.toByte()
            b[4] = operation.toByte()
            b[7] = status.toByte()
            b[19] = Crc8.calc(b, 0, 19).toByte()
            return b
        }

        fun parse(b: ByteArray): Head20? = if (b.size == SIZE) Head20(b.copyOf()) else null
    }
}

/** Status eines Bestätigungspakets (Byte 7): 0 = in Ordnung, sonst abgelehnt/Fehler. */
val Head20.confirmStatus: Int get() = bytes[7].toInt() and 0xFF

/** Eine vollständige Nachricht: Kopf + Daten. */
class Message(val head: Head20, val data: ByteArray) {
    val dataCrcValid: Boolean get() = Crc8.calc(data) == head.dataCrc
}

/**
 * Setzt Benachrichtigungen zu Nachrichten zusammen (wie `ReceivedDeviceDataCommand`): Sobald 20 Byte da sind, ist das der Kopf;
 * die Nachricht ist vollständig, wenn Kopf + Datenlänge vorliegen. Bei ungültigem Kopf-CRC wird ein Byte verworfen und neu angesetzt.
 */
class FrameAssembler(private val onConfirm: (Head20) -> Unit = {}) {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<Message> {
        buffer += chunk
        val out = ArrayList<Message>()
        while (buffer.size >= Head20.SIZE) {
            val head = Head20(buffer.copyOf(Head20.SIZE))
            if (!head.headerCrcValid) { // aus dem Takt: ein Byte weiter
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (head.type == Head20.TYPE_CONFIRM) { // Bestätigung des Geräts: nur der Kopf, keine Daten
                onConfirm(head)
                buffer = buffer.copyOfRange(Head20.SIZE, buffer.size)
                continue
            }
            val need = Head20.SIZE + head.dataLength
            if (buffer.size < need) break
            out += Message(head, buffer.copyOfRange(Head20.SIZE, need))
            buffer = buffer.copyOfRange(need, buffer.size)
        }
        return out
    }

    fun reset() { buffer = ByteArray(0) }
}

// ---- Protobuf (nur die benötigten Nachrichten, proto2) ---------------------------------------------------------------

internal class ProtoWriter {
    val out = ByteArrayOutputStream()

    private fun varintRaw(v0: Long) {
        var v = v0
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, v: Long) { varintRaw((field shl 3).toLong()); varintRaw(v) }
    fun bytes(field: Int, b: ByteArray) { varintRaw(((field shl 3) or 2).toLong()); varintRaw(b.size.toLong()); out.write(b) }
    fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
}

internal class ProtoReader(private val b: ByteArray) {
    var pos = 0
    val hasMore get() = pos < b.size

    fun varint(): Long {
        var shift = 0
        var v = 0L
        while (true) {
            if (pos >= b.size) throw IllegalArgumentException("Protobuf abgeschnitten")
            val x = b[pos++].toInt() and 0xFF
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return v
            shift += 7
            if (shift > 63) throw IllegalArgumentException("Varint zu lang")
        }
    }

    fun fixed64(): Long {
        if (pos + 8 > b.size) throw IllegalArgumentException("Protobuf abgeschnitten")
        var v = 0L
        for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun lengthDelimited(): ByteArray {
        val n = varint().toInt()
        if (n < 0 || pos + n > b.size) throw IllegalArgumentException("Protobuf abgeschnitten")
        return b.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> lengthDelimited()
            5 -> pos += 4
            else -> throw IllegalArgumentException("Unbekannter Wire-Typ $wire")
        }
    }
}

/** `cycling_data_file_flag_message`. */
data class FileFlag(val timestamp: Long, val fileSize: Long? = null, val userId: String? = null, val deviceId: String? = null) {
    internal fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, timestamp)
        fileSize?.let { varint(2, it) }
        userId?.let { string(3, it) }
        deviceId?.let { string(4, it) }
    }.out.toByteArray()

    companion object {
        internal fun decode(b: ByteArray): FileFlag {
            val r = ProtoReader(b)
            var ts = 0L; var size: Long? = null; var user: String? = null; var dev: String? = null
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (1 shl 3) -> ts = r.varint()
                    (2 shl 3) -> size = r.varint()
                    (3 shl 3) or 2 -> user = String(r.lengthDelimited(), Charsets.UTF_8)
                    (4 shl 3) or 2 -> dev = String(r.lengthDelimited(), Charsets.UTF_8)
                    else -> r.skip(tag and 7)
                }
            }
            return FileFlag(ts, size, user, dev)
        }
    }
}

/** `file_list_get_message` (Paging der Dateiliste). */
data class FileListGet(val fileNum: Long? = null, val supportNumMax: Long? = null, val indexStart: Long? = null, val indexEnd: Long? = null) {
    internal fun encode(): ByteArray = ProtoWriter().apply {
        fileNum?.let { varint(1, it) }
        supportNumMax?.let { varint(2, it) }
        indexStart?.let { varint(3, it) }
        indexEnd?.let { varint(4, it) }
    }.out.toByteArray()

    companion object {
        internal fun decode(b: ByteArray): FileListGet {
            val r = ProtoReader(b)
            var n: Long? = null; var m: Long? = null; var s: Long? = null; var e: Long? = null
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (1 shl 3) -> n = r.varint()
                    (2 shl 3) -> m = r.varint()
                    (3 shl 3) -> s = r.varint()
                    (4 shl 3) -> e = r.varint()
                    else -> r.skip(tag and 7)
                }
            }
            return FileListGet(n, m, s, e)
        }
    }
}

/** `cycling_data_msg` aus cycling_data.proto. */
data class CyclingDataMsg(
    val operation: Int,
    val files: List<FileFlag> = emptyList(),
    val fileContent: ByteArray? = null,
    val list: FileListGet? = null,
    val serviceType: Int = Service.CYCLING_DATA,
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, serviceType.toLong())
        varint(2, operation.toLong())
        files.forEach { bytes(3, it.encode()) }
        fileContent?.let { bytes(4, it) }
        list?.let { bytes(6, it.encode()) }
    }.out.toByteArray()

    override fun equals(other: Any?): Boolean {
        if (other !is CyclingDataMsg) return false
        val a = fileContent
        val b = other.fileContent
        val sameContent = if (a == null || b == null) a == b else a.contentEquals(b)
        return operation == other.operation && files == other.files && sameContent &&
            list == other.list && serviceType == other.serviceType
    }

    override fun hashCode() = 31 * (31 * operation + files.hashCode()) + (fileContent?.contentHashCode() ?: 0)

    companion object {
        fun decode(b: ByteArray): CyclingDataMsg {
            val r = ProtoReader(b)
            var service = Service.CYCLING_DATA; var op = 0
            val files = ArrayList<FileFlag>()
            var content: ByteArray? = null
            var list: FileListGet? = null
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (1 shl 3) -> service = r.varint().toInt()
                    (2 shl 3) -> op = r.varint().toInt()
                    (3 shl 3) or 2 -> files += FileFlag.decode(r.lengthDelimited())
                    (4 shl 3) or 2 -> content = r.lengthDelimited()
                    (6 shl 3) or 2 -> list = FileListGet.decode(r.lengthDelimited())
                    else -> r.skip(tag and 7)
                }
            }
            return CyclingDataMsg(op, files, content, list, service)
        }
    }
}


/**
 * Empfänger für Geräte mit **getrenntem Kopf- und Datenkanal** (iGPSPORT-Steuerkanal `…ca8e`, Datenkanal `…ca9e`):
 * Die Nutzlast kommt als Benachrichtigungen auf dem Datenkanal, der 20-Byte-Kopf (Typ 3) danach auf dem Steuerkanal.
 * Eine Nachricht ist fertig, sobald ein Kopf da ist **und** mindestens so viele Datenbytes vorliegen, wie er angibt
 * (die Reihenfolge von Kopf und Daten ist damit egal). Bestätigungen des Geräts (Typ 2) werden ignoriert.
 */
class SplitReceiver(private val onConfirm: (Head20) -> Unit = {}) {
    private var data = ByteArray(0)
    private val headers = ArrayDeque<Head20>()

    @Synchronized
    fun onData(chunk: ByteArray): List<Message> {
        // Das Gerät bestätigt (oder lehnt ab) jeden Befehl mit einem 20-Byte-Paket vom Typ 2 auf dem Datenkanal. Das sind keine Nutzdaten.
        if (isConfirm(chunk)) {
            onConfirm(Head20(chunk.copyOf()))
            return emptyList()
        }
        data += chunk
        return drain()
    }

    private fun isConfirm(c: ByteArray) =
        c.size == Head20.SIZE && c[0].toInt() == Head20.TYPE_CONFIRM && Crc8.calc(c, 0, 19) == (c[19].toInt() and 0xFF)

    @Synchronized
    fun onControl(chunk: ByteArray): List<Message> {
        val head = Head20.parse(chunk) ?: return emptyList()
        if (!head.headerCrcValid || head.type != Head20.TYPE_TRANSMIT_COMPLETE) return emptyList()
        headers.addLast(head)
        return drain()
    }

    @Synchronized
    fun reset() {
        data = ByteArray(0)
        headers.clear()
    }

    private fun drain(): List<Message> {
        val out = ArrayList<Message>()
        while (headers.isNotEmpty() && data.size >= headers.first().dataLength) {
            val head = headers.removeFirst()
            out += Message(head, data.copyOfRange(0, head.dataLength))
            data = data.copyOfRange(head.dataLength, data.size)
        }
        return out
    }
}


/** `ble_data_message`: Bindungs-/Verbindungsstatus (1 = gebunden bzw. verbunden) und Mitglieds-ID des Nutzers. */
data class BleData(val status: Long? = null, val memberId: String? = null)

/** `ble_msg` aus ble.proto (Bindung). */
data class BleMsg(val operation: Int, val data: List<BleData> = emptyList(), val serviceType: Int = Service.BLE) {
    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, serviceType.toLong())
        varint(2, operation.toLong())
        data.forEach { d ->
            bytes(3, ProtoWriter().apply {
                d.status?.let { varint(1, it) }
                d.memberId?.let { string(2, it) }
            }.out.toByteArray())
        }
    }.out.toByteArray()

    companion object {
        fun decode(b: ByteArray): BleMsg {
            val r = ProtoReader(b)
            var service = Service.BLE
            var op = 0
            val items = ArrayList<BleData>()
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (1 shl 3) -> service = r.varint().toInt()
                    (2 shl 3) -> op = r.varint().toInt()
                    (3 shl 3) or 2 -> {
                        val inner = ProtoReader(r.lengthDelimited())
                        var status: Long? = null
                        var member: String? = null
                        while (inner.hasMore) {
                            val t = inner.varint().toInt()
                            when (t) {
                                (1 shl 3) -> status = inner.varint()
                                (2 shl 3) or 2 -> member = String(inner.lengthDelimited(), Charsets.UTF_8)
                                else -> inner.skip(t and 7)
                            }
                        }
                        items += BleData(status, member)
                    }
                    else -> r.skip(tag and 7)
                }
            }
            return BleMsg(op, items, service)
        }
    }
}


/**
 * Datei-Empfang bei Geräten der Generation 4 (am BSC200 beobachtet): Auf den Kopf (`FILE_SEND`, Endetyp 3) folgt kein Protobuf, sondern
 * ein **roher Strom** mit einem kurzen Vorspann und der FIT-Datei. Der Kopf nennt nur die Länge des ersten Stücks, das Gerät sendet
 * danach ohne weitere Köpfe weiter. Die Datei ist vollständig, sobald die im FIT-Kopf angegebene Länge (Kopf + Daten + 2 Byte CRC) da ist.
 */
class RawFitStream {
    private var buffer: ByteArray? = null

    val active: Boolean get() = buffer != null

    /** Bisher empfangene Bytes des laufenden Stroms (0, wenn keiner läuft). */
    val receivedBytes: Int get() = buffer?.size ?: 0

    /** Erkennt den Kopf, der einen rohen Datei-Strom einleitet. */
    fun isStartHeader(chunk: ByteArray): Boolean {
        val head = Head20.parse(chunk.copyOf(minOf(chunk.size, Head20.SIZE))) ?: return false
        return chunk.size >= Head20.SIZE && head.headerCrcValid && head.service == Service.CYCLING_DATA &&
            head.operation == CyclingOp.FILE_SEND && head.endType == END_RAW
    }

    fun start(chunk: ByteArray): ByteArray? {
        buffer = ByteArray(0)
        return feed(chunk.copyOfRange(Head20.SIZE, chunk.size))
    }

    /** Liefert die fertige FIT-Datei oder null, wenn noch Daten fehlen. */
    fun feed(chunk: ByteArray): ByteArray? {
        val buf = (buffer ?: return null) + chunk
        buffer = buf
        val magic = indexOfFit(buf)
        if (magic >= 8) {
            val start = magic - 8
            val headerSize = buf[start].toInt() and 0xFF
            val dataSize = (0 until 4).sumOf { ((buf[start + 4 + it].toInt() and 0xFF).toLong()) shl (8 * it) }
            val total = headerSize + dataSize + 2
            if (buf.size - start >= total) {
                buffer = null
                return buf.copyOfRange(start, start + total.toInt())
            }
        }
        if (buf.size > MAX_RAW) buffer = null // kein FIT-Strom: aufgeben
        return null
    }

    fun reset() { buffer = null }

    private fun indexOfFit(b: ByteArray): Int {
        for (i in 0..b.size - 4) if (b[i] == '.'.code.toByte() && b[i + 1] == 'F'.code.toByte() && b[i + 2] == 'I'.code.toByte() && b[i + 3] == 'T'.code.toByte()) return i
        return -1
    }

    companion object {
        const val END_RAW = 3
        private const val MAX_RAW = 4_000_000
    }
}
