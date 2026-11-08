package io.github.xpertsavenue.ridevault.driver.igpsport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** Erwartungswerte stammen aus einer unabhängigen Python-Berechnung (siehe Entwicklungsprotokoll), nicht aus diesem Code. */
class ProtocolTest {

    @Test
    fun `CRC8 ist Maxim und stimmt mit der Tabelle der App ueberein`() {
        assertEquals(0xA1, Crc8.calc("123456789".toByteArray())) // Prüfwert von CRC-8/MAXIM-DOW
        // erste Tabelleneinträge aus der CRC8-Klasse der iGPSPORT-App: 00 5E BC E2 61 3F
        assertEquals(listOf(0x00, 0x5E, 0xBC, 0xE2, 0x61, 0x3F), (0..5).map { Crc8.calc(byteArrayOf(it.toByte())) })
    }

    @Test
    fun `Befehl LIST_GET entspricht dem Referenzkopf`() {
        val data = CyclingDataMsg(CyclingOp.LIST_GET).encode()
        assertEquals("08061001", data.hex()) // service_type=6, operate_type=1
        val head = Head20.build(Service.CYCLING_DATA, CyclingOp.LIST_GET, data)
        assertEquals("0106ffff01ffff00047f01ffffffffffffffffc4", head.hex())
    }

    @Test
    fun `Kopf wird gelesen und der Daten-CRC geprueft`() {
        val data = CyclingDataMsg(CyclingOp.LIST_GET).encode()
        val head = Head20.parse(Head20.build(Service.CYCLING_DATA, CyclingOp.LIST_GET, data))!!
        assertEquals(1, head.type); assertEquals(6, head.service); assertEquals(1, head.operation)
        assertEquals(4, head.dataLength); assertEquals(1, head.endType); assertTrue(head.headerCrcValid)
        assertTrue(Message(head, data).dataCrcValid)
        assertFalse(Message(head, byteArrayOf(1, 2, 3, 4)).dataCrcValid)
    }

    @Test
    fun `Bestaetigung entspricht dem Referenzpaket`() {
        assertEquals("0206ffff04ffff00ffffffffffffffffffffff2e", Head20.confirm(6, CyclingOp.FILE_SEND, status = 0).hex())
        assertEquals(1, Head20.parse(Head20.confirm(6, 4, status = 1))!!.let { it.bytes[7].toInt() })
        assertTrue(Head20.parse(Head20.confirm(6, 4, 0))!!.headerCrcValid)
    }

    @Test
    fun `FILE_GET mit Zeitstempel entspricht der Referenz-Protobuf-Kodierung`() {
        val msg = CyclingDataMsg(CyclingOp.FILE_GET, files = listOf(FileFlag(0x12345678, fileSize = 1000)))
        assertEquals("080610031a0908f8acd1910110e807", msg.encode().hex())
        assertEquals(msg, CyclingDataMsg.decode(msg.encode()))
    }

    @Test
    fun `Protobuf-Hin-und-zurueck mit allen Feldern und unbekannten Feldern`() {
        val msg = CyclingDataMsg(
            CyclingOp.FILE_SEND,
            files = listOf(FileFlag(1_700_000_000, 4096, "u1", "dev9")),
            fileContent = byteArrayOf(1, 2, 3, 0x7F, -1),
            list = FileListGet(fileNum = 5, supportNumMax = 10, indexStart = 0, indexEnd = 4),
        )
        assertEquals(msg, CyclingDataMsg.decode(msg.encode()))
        // unbekanntes Feld 15 (Varint) dahinter wird übersprungen
        val withExtra = msg.encode() + hex("7805")
        assertEquals(msg, CyclingDataMsg.decode(withExtra))
    }

    @Test
    fun `Bindungsnachricht hin und zurueck, BOND_INFO entspricht dem Referenzwert`() {
        assertEquals("080a1001", BleMsg(BleOp.BOND_INFO).encode().hex()) // service_type=10, ble_operate_type=1
        val msg = BleMsg(BleOp.CONNECT_STATUS, listOf(BleData(1, "abc")))
        assertEquals(msg, BleMsg.decode(msg.encode()))
        assertEquals("080a10031a0708011203616263", msg.encode().hex())
    }

    @Test
    fun `FrameAssembler setzt Nachrichten aus beliebigen Blockgroessen zusammen`() {
        val data = CyclingDataMsg(CyclingOp.FILE_SEND, fileContent = ByteArray(300) { it.toByte() }).encode()
        val frame = Head20.build(Service.CYCLING_DATA, CyclingOp.FILE_SEND, data) + data
        val asm = FrameAssembler()
        val got = ArrayList<Message>()
        frame.toList().chunked(20).forEach { got += asm.feed(it.toByteArray()) }
        assertEquals(1, got.size)
        assertContentEquals(data, got[0].data)
        assertTrue(got[0].dataCrcValid)
    }

    @Test
    fun `zwei Nachrichten hintereinander und Wiedereinstieg nach Muell`() {
        val d1 = CyclingDataMsg(CyclingOp.LIST_SEND, files = listOf(FileFlag(1, 10))).encode()
        val d2 = CyclingDataMsg(CyclingOp.LIST_SEND, files = listOf(FileFlag(2, 20))).encode()
        val m1 = Head20.build(6, CyclingOp.LIST_SEND, d1) + d1
        val m2 = Head20.build(6, CyclingOp.LIST_SEND, d2) + d2
        val asm = FrameAssembler()
        val got = asm.feed(byteArrayOf(0x55, 0x66, 0x77) + m1 + m2)
        assertEquals(2, got.size)
        assertEquals(1L, CyclingDataMsg.decode(got[0].data).files.single().timestamp)
        assertEquals(2L, CyclingDataMsg.decode(got[1].data).files.single().timestamp)
    }
}


class SplitReceiverTest {
    private fun message(op: Int, data: ByteArray) = Head20.build(Service.CYCLING_DATA, op, data, type = Head20.TYPE_TRANSMIT_COMPLETE) to data

    @Test
    fun `Daten vor Kopf ergeben eine Nachricht`() {
        val (head, data) = message(CyclingOp.LIST_SEND, CyclingDataMsg(CyclingOp.LIST_SEND, files = listOf(FileFlag(7, 9))).encode())
        val r = SplitReceiver()
        assertEquals(0, r.onData(data).size)
        val got = r.onControl(head)
        assertEquals(1, got.size)
        assertContentEquals(data, got[0].data)
        assertTrue(got[0].dataCrcValid)
    }

    @Test
    fun `Kopf vor Daten, Daten in Stuecken`() {
        val (head, data) = message(CyclingOp.FILE_SEND, ByteArray(100) { it.toByte() })
        val r = SplitReceiver()
        assertEquals(0, r.onControl(head).size)
        assertEquals(0, r.onData(data.copyOfRange(0, 60)).size)
        val got = r.onData(data.copyOfRange(60, 100))
        assertEquals(1, got.size)
        assertContentEquals(data, got[0].data)
    }

    @Test
    fun `zwei Nachrichten nacheinander und Bestaetigungen des Geraets werden ignoriert`() {
        val (h1, d1) = message(CyclingOp.FILE_SEND, byteArrayOf(1, 2, 3))
        val (h2, d2) = message(CyclingOp.FILE_SEND, byteArrayOf(9, 8))
        val r = SplitReceiver()
        assertEquals(0, r.onControl(Head20.confirm(6, 4, 0)).size) // Typ 2 ignoriert
        val got = r.onData(d1 + d2).size + r.onControl(h1).size + r.onControl(h2).size
        assertEquals(2, got)
    }

    @Test
    fun `Kopf mit falschem CRC wird verworfen`() {
        val (head, _) = message(CyclingOp.FILE_SEND, byteArrayOf(1))
        head[19] = (head[19] + 1).toByte()
        assertEquals(0, SplitReceiver().onControl(head).size)
    }
}

class ConfigMsgTest {
    @Test
    fun `Einheiten-Abfrage wird so codiert wie in config proto`() {
        // service_type = 12, config_sevice_type = 4 (UNIT), config_operate_type = 2 (GET)
        assertEquals("080c10041802", ConfigMsg(ConfigService.UNIT, ConfigOp.GET).encode().joinToString("") { "%02x".format(it) })
        // SET mit unit_message { unit_item = 1, unit_type = 2 }: Feld 7 (Tag 0x3a), Länge 4
        assertEquals(
            "080c100418013a0408011002",
            ConfigMsg(ConfigService.UNIT, ConfigOp.SET, units = listOf(1 to 2)).encode().joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `Konfigurationsnachricht laesst sich umkehren`() {
        val msg = ConfigMsg(
            ConfigService.BACKLIGHT, ConfigOp.SET,
            backlight = BacklightData(nightOn = 1, timeSeconds = -1, auto = 0, dayPercent = 60, nightPercent = 30),
            units = listOf(1 to 2, 4 to 1),
            autos = listOf(AutoItem(AutoType.PAUSE, 1, 5)),
            sounds = listOf(SoundItem(SoundType.KEY, 1, 0)),
            user = UserData(sex = 1, weight100g = 705, age = 42, height = 180),
            language = 4,
        )
        val back = ConfigMsg.decode(msg.encode())
        assertEquals(msg.copy(operation = ConfigOp.SET), back)
    }

    @Test
    fun `Seiten, Fahrmodi und Seitenfaehigkeiten lassen sich umkehren`() {
        val msg = ConfigMsg(
            ConfigService.PAGE, ConfigOp.SET, operateMode = 2,
            pages = listOf(
                PageMsg(index = 1, status = 1, data = listOf(0x101, 0x501, 0x703), name = "Road", type = 1, mode = 0x49, lineWidth = 0x249, mainPage = 1),
                PageMsg(index = 2, status = 0, data = listOf(0x601), type = 2, mode = 1, lineWidth = 4, dataSite = 1, graphicDisplay = 0),
            ),
            modes = listOf(ModeMsg(index = 2, color = 5, valid = 1, inUse = 1, template = 0, name = "Tour")),
        )
        assertEquals(msg, ConfigMsg.decode(msg.encode()))
    }

    @Test
    fun `gepackte Listen in den Seitenfaehigkeiten werden gelesen`() {
        // config_msg: service 12, config 2, op 6; Feld 8 (cur_page_status_msg): Zeilen max 4, unsupport_data gepackt [0x101, 0x102] ...
        val status = byteArrayOf(0x08, 4, 0x22, 4, 0x81.toByte(), 0x02, 0x82.toByte(), 0x02, 0x32, 2, 0x49, 0x0A)
        val body = byteArrayOf(0x08, 12, 0x10, 2, 0x18, 6, 0x42, status.size.toByte()) + status
        val back = ConfigMsg.decode(body)
        assertEquals(4, back.pageStatus?.lineMax)
        assertEquals(listOf(0x101, 0x102), back.pageStatus?.unsupportedData)
        assertEquals(listOf(0x49, 0x0A), back.pageStatus?.layouts)
    }

    @Test
    fun `Seriennummer wird angefragt und aus der Antwort gelesen`() {
        // Anfrage: service_type = 11, factory_operate_type = 1 (SN_GET)
        assertEquals("080b1001", FactoryMsg.requestSerial().joinToString("") { "%02x".format(it) })
        // Antwort: Operation 2 (SN_SEND), Feld 3 mit factory_sn_message { sn = "BSC200ABC123" }
        val sn = "BSC200ABC123".toByteArray()
        val inner = byteArrayOf(0x0A, sn.size.toByte()) + sn
        val body = byteArrayOf(0x08, 0x0B, 0x10, 0x02, 0x1A, inner.size.toByte()) + inner
        assertEquals("BSC200ABC123", FactoryMsg.decodeSerial(body))
        assertEquals(null, FactoryMsg.decodeSerial(byteArrayOf(0x08, 0x0B, 0x10, 0x02)))
    }

    @Test
    fun `Streckenliste wird angefragt und mit Fließkommakoordinaten gelesen`() {
        assertEquals("08071001", RoutePlanMsg(RouteOp.LIST_GET).encode().joinToString("") { "%02x".format(it) })
        // Antwort mit einer Strecke: Id 7, Typ 2 (GPX), Name "Test", Länge 1500 m, Start 11.0 / 50.5
        val inner = ProtoWriter().apply {
            varint(1, 7); varint(2, 2); string(3, "Test"); varint(4, 1500)
        }.out.toByteArray() +
            byteArrayOf(0x29) + java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(11.0).array() +
            byteArrayOf(0x31) + java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(50.5).array()
        val body = byteArrayOf(0x08, 0x07, 0x10, 0x02, 0x2A, inner.size.toByte()) + inner
        val route = RoutePlanMsg.decode(body).infos.single()
        assertEquals(7L, route.id); assertEquals(2, route.fileType); assertEquals("Test", route.name); assertEquals(1500L, route.totalDistanceM)
        assertEquals(11.0, route.longitudeStart); assertEquals(50.5, route.latitudeStart)
    }

    @Test
    fun `Dateistrom fuer Strecken besteht aus Kopf, Laenge, Beschreibung und Inhalt`() {
        val content = "<gpx/>".toByteArray()
        val stream = FileOperation.buildStream(123, "Route", "gpx", content)
        val head = Head20.parse(stream.copyOf(20))!!
        assertEquals(Service.FILE_OPERATION, head.service)
        assertEquals(FileOperation.OP_ADD, head.operation)
        assertEquals(0xAA, head.fileTag)
        assertTrue(head.headerCrcValid)
        val pbLen = ((stream[20].toInt() and 0xFF) shl 24) or ((stream[21].toInt() and 0xFF) shl 16) or ((stream[22].toInt() and 0xFF) shl 8) or (stream[23].toInt() and 0xFF)
        assertEquals(24 + pbLen + content.size, stream.size)
        assertEquals(content.toList(), stream.copyOfRange(24 + pbLen, stream.size).toList())
    }
}
