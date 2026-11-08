package io.github.xpertsavenue.ridevault.driver.igpsport

/** `ROUTE_PLAN_OPERATE_TYPE` (route_plan.proto). */
object RouteOp {
    const val LIST_GET = 1
    const val LIST_SEND = 2
    const val FILE_DEL = 3
    const val FILE_SEND = 4
    const val FILE_USE = 5
    const val FILES_DEL = 6
    const val LIST_NUM_GET = 7
    const val RENAME = 8
}

/** `route_plan_info_message`: eine Strecke auf dem Gerät. */
data class RouteInfoMsg(
    val id: Long? = null,
    val fileType: Int? = null,
    val name: String? = null,
    val totalDistanceM: Long? = null,
    val longitudeStart: Double? = null,
    val latitudeStart: Double? = null,
    val status: Int? = null,
)

/** `file_list_get_message` (common.proto): Anzahl und Bereich der Liste. */
data class ListRange(val fileNum: Int? = null, val supportMax: Int? = null, val start: Int? = null, val end: Int? = null)

/** `route_plan_data_msg` (Dienst 7, Strecken): Liste lesen, Strecke löschen. Das Senden der Datei läuft über den Dateidienst ([FileOperation]). */
data class RoutePlanMsg(
    val operation: Int,
    val infos: List<RouteInfoMsg> = emptyList(),
    val range: ListRange? = null,
    /** `line_id` (Feld 3): Dateikennung "Nummer.Endung", z. B. "1794139194.gpx" (nötig zum Löschen). */
    val lineIds: List<String> = emptyList(),
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, Service.ROUTE_PLAN.toLong())
        varint(2, operation.toLong())
        lineIds.forEach { string(3, it) }
        infos.forEach { i ->
            bytes(5, ProtoWriter().apply {
                i.id?.let { varint(1, it) }
                i.fileType?.let { varint(2, it.toLong()) }
                i.name?.let { string(3, it) }
                i.totalDistanceM?.let { varint(4, it) }
                i.status?.let { varint(7, it.toLong()) }
            }.out.toByteArray())
        }
        range?.let { g ->
            bytes(12, ProtoWriter().apply {
                g.fileNum?.let { varint(1, it.toLong()) }
                g.supportMax?.let { varint(2, it.toLong()) }
                g.start?.let { varint(3, it.toLong()) }
                g.end?.let { varint(4, it.toLong()) }
            }.out.toByteArray())
        }
    }.out.toByteArray()

    companion object {
        fun decode(b: ByteArray): RoutePlanMsg {
            val r = ProtoReader(b)
            var op = 0
            val infos = ArrayList<RouteInfoMsg>()
            var range: ListRange? = null
            val lineIds = ArrayList<String>()
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (2 shl 3) -> op = r.varint().toInt()
                    (3 shl 3) or 2 -> lineIds += String(r.lengthDelimited(), Charsets.UTF_8)
                    (5 shl 3) or 2 -> infos += decodeInfo(r.lengthDelimited())
                    (12 shl 3) or 2 -> {
                        val inner = ProtoReader(r.lengthDelimited())
                        var num: Int? = null; var max: Int? = null; var st: Int? = null; var en: Int? = null
                        while (inner.hasMore) {
                            val t = inner.varint().toInt()
                            when (t ushr 3) {
                                1 -> num = inner.varint().toInt()
                                2 -> max = inner.varint().toInt()
                                3 -> st = inner.varint().toInt()
                                4 -> en = inner.varint().toInt()
                                else -> inner.skip(t and 7)
                            }
                        }
                        range = ListRange(num, max, st, en)
                    }
                    else -> r.skip(tag and 7)
                }
            }
            return RoutePlanMsg(op, infos, range, lineIds)
        }

        private fun decodeInfo(b: ByteArray): RouteInfoMsg {
            val r = ProtoReader(b)
            var id: Long? = null; var type: Int? = null; var name: String? = null; var dist: Long? = null
            var lon: Double? = null; var lat: Double? = null; var status: Int? = null
            while (r.hasMore) {
                val t = r.varint().toInt()
                when (t) {
                    (1 shl 3) -> id = r.varint()
                    (2 shl 3) -> type = r.varint().toInt()
                    (3 shl 3) or 2 -> name = String(r.lengthDelimited(), Charsets.UTF_8)
                    (4 shl 3) -> dist = r.varint()
                    (5 shl 3) or 1 -> lon = java.lang.Double.longBitsToDouble(r.fixed64())
                    (6 shl 3) or 1 -> lat = java.lang.Double.longBitsToDouble(r.fixed64())
                    (7 shl 3) -> status = r.varint().toInt()
                    else -> r.skip(t and 7)
                }
            }
            return RouteInfoMsg(id, type, name, dist, lon, lat, status)
        }
    }
}

/**
 * Allgemeiner Dateidienst (Dienst 21, general_file_operation.proto), wie ihn die Hersteller-App für Strecken auf Geräten der
 * neuen Generation nutzt (`sendRoutePlanFileSingleChannel`): ein einziger Strom aus
 * 20-Byte-Kopf (Dienst 21, Operation ADD = 3, Dateimarke 0xAA, Datenlänge 0) + 4 Byte Länge der Beschreibung (Big-Endian)
 * + Beschreibung (`general_file_operation`) + Dateiinhalt. Die Antwort kommt mit Dienst 21, Operation 3; Statusbyte 0 = in Ordnung.
 */
object FileOperation {
    const val FILE_TYPE_ROUTE_PLAN = 2
    const val OP_ADD = 3
    const val TAG_STREAM = 0xAA

    /** Beschreibung der Datei als Protobuf. */
    fun describe(fileId: Int, size: Int, name: String, extension: String): ByteArray = ProtoWriter().apply {
        varint(1, Service.FILE_OPERATION.toLong())
        varint(2, OP_ADD.toLong())
        varint(3, FILE_TYPE_ROUTE_PLAN.toLong())
        varint(4, size.toLong())
        varint(5, fileId.toLong() and 0xFFFFFFFFL)
        string(6, name)
        string(7, extension)
    }.out.toByteArray()

    /** Der vollständige zu sendende Strom. */
    fun buildStream(fileId: Int, name: String, extension: String, content: ByteArray): ByteArray {
        val pb = describe(fileId, content.size, name, extension)
        val head = Head20.build(Service.FILE_OPERATION, OP_ADD, ByteArray(0), fileTag = TAG_STREAM)
        val len = byteArrayOf((pb.size ushr 24).toByte(), (pb.size ushr 16).toByte(), (pb.size ushr 8).toByte(), pb.size.toByte())
        return head + len + pb + content
    }
}

/** Dateiendung zu einem `ROUTE_PLAN_FILE_TYPE` (1 CNX, 2 GPX, 3 FIT, 4 TCX, 5 XML). */
fun routeExtension(fileType: Int?): String = when (fileType) { 1 -> "cnx"; 2 -> "gpx"; 3 -> "fit"; 4 -> "tcx"; 5 -> "xml"; else -> "gpx" }
