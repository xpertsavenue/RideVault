package io.github.xpertsavenue.ridevault.driver.igpsport

/** `CONFIG_SERVICE_TYPE` aus config.proto (nur die genutzten). */
object ConfigService {
    const val USER = 0x01
    const val PAGE = 0x02
    const val UNIT = 0x04
    const val LANG = 0x05
    const val SOUND = 0x11
    const val BACKLIGHT = 0x14
    const val AUTO = 0x32
    const val MODE = 0x33
}

/** `CONFIG_OPERATE_TYPE`. */
object ConfigOp {
    const val SET = 1
    const val GET = 2
    const val GET_MODULE_INFO = 6
}

/** `UNIT_ITEM`. */
object UnitItem {
    const val DISTANCE = 1
    const val ELEVATION = 2
    const val WEIGHT = 3
    const val TEMPERATURE = 4
}

/** `AUTO_TYPE`. */
object AutoType {
    const val PAUSE = 1
    const val RECORD_START = 2
    const val POWER_OFF = 3
    const val SLEEP = 4
    const val HOME_PAGE_BACK = 5
    const val SMART_SAVE = 6
    const val POWER_SAVE = 7
    const val PAGE_AUTO = 8
}

/** `SOUND_TYPE` (Bitmaske). */
object SoundType {
    const val RECORD_START = 0x01
    const val RECORD_PAUSE = 0x02
    const val RECORD_SAVE = 0x04
    const val LAP = 0x08
    const val ALERT = 0x10
    const val KEY = 0x20
}

/** `backlight_msg`; fehlende Felder sind null. [timeSeconds] 0xFFFFFFFF = immer an (hier als -1). */
data class BacklightData(
    val nightOn: Int? = null,
    val timeSeconds: Long? = null,
    val auto: Int? = null,
    val dayPercent: Int? = null,
    val nightPercent: Int? = null,
)

/** `user_data_msg` (ohne Mitglieds-ID und Zonen). [weight100g] in 100 g. */
data class UserData(val sex: Int? = null, val weight100g: Int? = null, val age: Int? = null, val height: Int? = null)

/** `auto_set_msg`. */
data class AutoItem(val type: Int, val status: Int, val param1: Int? = null)

/** `sound_set_msg`; [scene] 1 = alle Situationen. */
data class SoundItem(val type: Int, val scene: Int?, val status: Int)

/** `page_msg`: eine Seite. Fehlende Felder sind null. */
data class PageMsg(
    val index: Int? = null,
    val status: Int? = null,
    val data: List<Int> = emptyList(),
    val dataSite: Int? = null,
    val name: String? = null,
    val type: Int? = null,
    val mode: Int? = null,
    val mainPage: Int? = null,
    val lineWidth: Int? = null,
    val graphicDisplay: Int? = null,
)

/** `cur_page_status_msg`: was das Gerät für Seiten unterstützt. */
data class PageStatusMsg(
    val lineMax: Int? = null,
    val lineDataMax: Int? = null,
    val dataPageMax: Int? = null,
    val unsupportedData: List<Int> = emptyList(),
    val lcdType: Int? = null,
    val layouts: List<Int> = emptyList(),
)

/** `mode_msg`: ein Fahrmodus. */
data class ModeMsg(val index: Int? = null, val color: Int? = null, val valid: Int? = null, val inUse: Int? = null, val template: Int? = null, val name: String? = null)

/**
 * `config_msg` (config.proto): Dienst 12, zweites Dienstbyte im Kopf = [configService], Operation im Kopf = [operation].
 * Es sind nur die Teile enthalten, die RideVault nutzt.
 */
data class ConfigMsg(
    val configService: Int,
    val operation: Int,
    val user: UserData? = null,
    val units: List<Pair<Int, Int>> = emptyList(),
    val language: Int? = null,
    val supportedLanguages: List<Int> = emptyList(),
    val backlight: BacklightData? = null,
    val autos: List<AutoItem> = emptyList(),
    val sounds: List<SoundItem> = emptyList(),
    val pages: List<PageMsg> = emptyList(),
    val pageStatus: PageStatusMsg? = null,
    /** `cur_operate_mode` (Feld 19): für welchen Fahrmodus Seiten gelesen/geschrieben werden. */
    val operateMode: Int? = null,
    val modes: List<ModeMsg> = emptyList(),
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, Service.CONFIG.toLong())
        varint(2, configService.toLong())
        varint(3, operation.toLong())
        user?.let { u ->
            bytes(4, ProtoWriter().apply {
                u.sex?.let { varint(1, it.toLong()) }
                u.weight100g?.let { varint(2, it.toLong()) }
                u.age?.let { varint(3, it.toLong()) }
                u.height?.let { varint(4, it.toLong()) }
            }.out.toByteArray())
        }
        units.forEach { (item, type) -> bytes(7, ProtoWriter().apply { varint(1, item.toLong()); varint(2, type.toLong()) }.out.toByteArray()) }
        language?.let { lang -> bytes(12, ProtoWriter().apply { varint(1, lang.toLong()) }.out.toByteArray()) }
        backlight?.let { b ->
            bytes(13, ProtoWriter().apply {
                b.nightOn?.let { varint(1, it.toLong()) }
                b.timeSeconds?.let { varint(2, if (it < 0) 0xFFFFFFFFL else it) }
                b.auto?.let { varint(3, it.toLong()) }
                b.dayPercent?.let { varint(4, it.toLong()) }
                b.nightPercent?.let { varint(5, it.toLong()) }
            }.out.toByteArray())
        }
        autos.forEach { a ->
            bytes(23, ProtoWriter().apply {
                varint(1, a.type.toLong()); varint(2, a.status.toLong())
                a.param1?.let { varint(3, it.toLong()) }
            }.out.toByteArray())
        }
        sounds.forEach { s ->
            bytes(25, ProtoWriter().apply {
                varint(1, s.type.toLong())
                s.scene?.let { varint(2, it.toLong()) }
                varint(3, s.status.toLong())
            }.out.toByteArray())
        }
        pages.forEach { p ->
            bytes(5, ProtoWriter().apply {
                p.index?.let { varint(1, it.toLong()) }
                p.status?.let { varint(2, it.toLong()) }
                p.data.forEach { varint(3, it.toLong()) }
                p.dataSite?.let { varint(4, it.toLong()) }
                p.name?.let { string(5, it) }
                p.type?.let { varint(6, it.toLong()) }
                p.mode?.let { varint(7, it.toLong()) }
                p.mainPage?.let { varint(8, it.toLong()) }
                p.lineWidth?.let { varint(9, it.toLong()) }
                p.graphicDisplay?.let { varint(10, it.toLong()) }
            }.out.toByteArray())
        }
        operateMode?.let { varint(19, it.toLong()) }
        modes.forEach { m ->
            bytes(20, ProtoWriter().apply {
                m.index?.let { varint(1, it.toLong()) }
                m.color?.let { varint(2, it.toLong()) }
                m.valid?.let { varint(3, it.toLong()) }
                m.inUse?.let { varint(4, it.toLong()) }
                m.template?.let { varint(5, it.toLong()) }
                m.name?.let { string(6, it) }
            }.out.toByteArray())
        }
    }.out.toByteArray()

    companion object {
        fun decode(b: ByteArray): ConfigMsg {
            val r = ProtoReader(b)
            var service = 0
            var op = 0
            var user: UserData? = null
            val units = ArrayList<Pair<Int, Int>>()
            var lang: Int? = null
            val supported = ArrayList<Int>()
            var backlight: BacklightData? = null
            val autos = ArrayList<AutoItem>()
            val sounds = ArrayList<SoundItem>()
            val pages = ArrayList<PageMsg>()
            var pageStatus: PageStatusMsg? = null
            var operateMode: Int? = null
            val modes = ArrayList<ModeMsg>()
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (2 shl 3) -> service = r.varint().toInt()
                    (3 shl 3) -> op = r.varint().toInt()
                    (4 shl 3) or 2 -> user = decodeUser(r.lengthDelimited())
                    (7 shl 3) or 2 -> {
                        val f = fields(r.lengthDelimited())
                        val item = f[1]?.toInt()
                        val type = f[2]?.toInt()
                        if (item != null && type != null) units += item to type
                    }
                    (12 shl 3) or 2 -> {
                        val inner = ProtoReader(r.lengthDelimited())
                        while (inner.hasMore) {
                            val t = inner.varint().toInt()
                            when (t) {
                                (1 shl 3) -> lang = inner.varint().toInt()
                                (2 shl 3) -> supported += inner.varint().toInt()
                                (2 shl 3) or 2 -> { // gepackte Liste
                                    val p = ProtoReader(inner.lengthDelimited())
                                    while (p.hasMore) supported += p.varint().toInt()
                                }
                                else -> inner.skip(t and 7)
                            }
                        }
                    }
                    (13 shl 3) or 2 -> {
                        val f = fields(r.lengthDelimited())
                        backlight = BacklightData(
                            nightOn = f[1]?.toInt(),
                            timeSeconds = f[2]?.let { if (it >= 0xFFFFFFFFL) -1L else it },
                            auto = f[3]?.toInt(), dayPercent = f[4]?.toInt(), nightPercent = f[5]?.toInt(),
                        )
                    }
                    (23 shl 3) or 2 -> {
                        val f = fields(r.lengthDelimited())
                        val type = f[1]?.toInt()
                        if (type != null) autos += AutoItem(type, f[2]?.toInt() ?: 0, f[3]?.toInt())
                    }
                    (25 shl 3) or 2 -> {
                        val f = fields(r.lengthDelimited())
                        val type = f[1]?.toInt()
                        if (type != null) sounds += SoundItem(type, f[2]?.toInt(), f[3]?.toInt() ?: 0)
                    }
                    (5 shl 3) or 2 -> pages += decodePage(r.lengthDelimited())
                    (8 shl 3) or 2 -> pageStatus = decodePageStatus(r.lengthDelimited())
                    (19 shl 3) -> operateMode = r.varint().toInt()
                    (20 shl 3) or 2 -> modes += decodeMode(r.lengthDelimited())
                    else -> r.skip(tag and 7)
                }
            }
            return ConfigMsg(service, op, user, units, lang, supported, backlight, autos, sounds, pages, pageStatus, operateMode, modes)
        }

        /** Liest ein Zahlenfeld, das einzeln oder als gepackte Liste vorkommen kann. */
        private fun readRepeated(r: ProtoReader, tag: Int, out: MutableList<Int>) {
            if (tag and 7 == 2) {
                val p = ProtoReader(r.lengthDelimited())
                while (p.hasMore) out += p.varint().toInt()
            } else {
                out += r.varint().toInt()
            }
        }

        private fun decodePage(b: ByteArray): PageMsg {
            val r = ProtoReader(b)
            var index: Int? = null; var status: Int? = null; val data = ArrayList<Int>(); var site: Int? = null; var name: String? = null
            var type: Int? = null; var mode: Int? = null; var main: Int? = null; var width: Int? = null; var graphic: Int? = null
            while (r.hasMore) {
                val t = r.varint().toInt()
                when (t ushr 3) {
                    1 -> index = r.varint().toInt()
                    2 -> status = r.varint().toInt()
                    3 -> readRepeated(r, t, data)
                    4 -> site = r.varint().toInt()
                    5 -> name = String(r.lengthDelimited(), Charsets.UTF_8)
                    6 -> type = r.varint().toInt()
                    7 -> mode = r.varint().toInt()
                    8 -> main = r.varint().toInt()
                    9 -> width = r.varint().toInt()
                    10 -> graphic = r.varint().toInt()
                    else -> r.skip(t and 7)
                }
            }
            return PageMsg(index, status, data, site, name, type, mode, main, width, graphic)
        }

        private fun decodePageStatus(b: ByteArray): PageStatusMsg {
            val r = ProtoReader(b)
            var lineMax: Int? = null; var lineData: Int? = null; var pageMax: Int? = null; var lcd: Int? = null
            val unsupported = ArrayList<Int>(); val layouts = ArrayList<Int>()
            while (r.hasMore) {
                val t = r.varint().toInt()
                when (t ushr 3) {
                    1 -> lineMax = r.varint().toInt()
                    2 -> lineData = r.varint().toInt()
                    3 -> pageMax = r.varint().toInt()
                    4 -> readRepeated(r, t, unsupported)
                    5 -> lcd = r.varint().toInt()
                    6 -> readRepeated(r, t, layouts)
                    else -> r.skip(t and 7)
                }
            }
            return PageStatusMsg(lineMax, lineData, pageMax, unsupported, lcd, layouts)
        }

        private fun decodeMode(b: ByteArray): ModeMsg {
            val r = ProtoReader(b)
            var index: Int? = null; var color: Int? = null; var valid: Int? = null; var inUse: Int? = null; var template: Int? = null; var name: String? = null
            while (r.hasMore) {
                val t = r.varint().toInt()
                when (t ushr 3) {
                    1 -> index = r.varint().toInt()
                    2 -> color = r.varint().toInt()
                    3 -> valid = r.varint().toInt()
                    4 -> inUse = r.varint().toInt()
                    5 -> template = r.varint().toInt()
                    6 -> name = String(r.lengthDelimited(), Charsets.UTF_8)
                    else -> r.skip(t and 7)
                }
            }
            return ModeMsg(index, color, valid, inUse, template, name)
        }

        private fun decodeUser(b: ByteArray): UserData {
            val f = fields(b)
            return UserData(f[1]?.toInt(), f[2]?.toInt(), f[3]?.toInt(), f[4]?.toInt())
        }

        /** Liest eine flache Nachricht aus Zahlenfeldern (Feldnummer -> Wert); andere Felder werden übersprungen. */
        private fun fields(b: ByteArray): Map<Int, Long> {
            val out = HashMap<Int, Long>()
            val r = ProtoReader(b)
            while (r.hasMore) {
                val t = r.varint().toInt()
                if (t and 7 == 0) out[t ushr 3] = r.varint() else r.skip(t and 7)
            }
            return out
        }
    }
}

/**
 * `factory_msg` (factory.proto), hier nur zum **Lesen der Seriennummer**: Dienst 11, Operation 1 (SN_GET); die Antwort (Operation 2)
 * trägt in Feld 3 je eine `factory_sn_message` mit dem Text der Seriennummer in Feld 1. Es wird nie in den Werksdienst geschrieben.
 */
object FactoryMsg {
    const val SN_GET = 1
    const val MEMORY_GET = 5
    const val BATTERY_GET = 6

    fun request(op: Int): ByteArray = ProtoWriter().apply {
        varint(1, Service.FACTORY.toLong())
        varint(2, op.toLong())
    }.out.toByteArray()

    /** Zwei Zahlen (Feld 1 und 2) aus der verschachtelten Nachricht in [field]; null, wenn sie fehlt. */
    private fun pair(b: ByteArray, field: Int): Pair<Long?, Long?>? {
        val r = ProtoReader(b)
        while (r.hasMore) {
            val tag = r.varint().toInt()
            if (tag == (field shl 3) or 2) {
                val inner = ProtoReader(r.lengthDelimited())
                var a: Long? = null; var c: Long? = null
                while (inner.hasMore) {
                    val t = inner.varint().toInt()
                    when (t) {
                        (1 shl 3) -> a = inner.varint()
                        (2 shl 3) -> c = inner.varint()
                        else -> inner.skip(t and 7)
                    }
                }
                return a to c
            } else r.skip(tag and 7)
        }
        return null
    }

    /** Akku: Spannung in mV und Ladung in Prozent. */
    fun decodeBattery(b: ByteArray): Pair<Int?, Int?>? = pair(b, 6)?.let { it.first?.toInt() to it.second?.toInt() }

    /** Speicher: Gesamtgröße und freier Teil (gleiche Einheit). */
    fun decodeMemory(b: ByteArray): Pair<Long?, Long?>? = pair(b, 5)

    fun requestSerial(): ByteArray = ProtoWriter().apply {
        varint(1, Service.FACTORY.toLong())
        varint(2, SN_GET.toLong())
    }.out.toByteArray()

    /** Seriennummer aus der Antwort oder null, wenn keine enthalten ist. */
    fun decodeSerial(b: ByteArray): String? {
        val r = ProtoReader(b)
        while (r.hasMore) {
            val tag = r.varint().toInt()
            if (tag == (3 shl 3) or 2) {
                val inner = ProtoReader(r.lengthDelimited())
                while (inner.hasMore) {
                    val t = inner.varint().toInt()
                    if (t == (1 shl 3) or 2) {
                        val sn = String(inner.lengthDelimited(), Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                        if (sn.isNotEmpty()) return sn
                    } else inner.skip(t and 7)
                }
            } else r.skip(tag and 7)
        }
        return null
    }
}
