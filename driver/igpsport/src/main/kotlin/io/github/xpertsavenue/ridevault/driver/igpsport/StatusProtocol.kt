package io.github.xpertsavenue.ridevault.driver.igpsport

/** Operationen des Gerätestatus-Dienstes (`DEV_STATUS_OPERATE_TYPE`). */
object DevStatusOp {
    const val GET = 1
    const val SEND = 2
}

/** `rt_data_message`: Livewerte des Geräts (Einheiten wie im Protokoll: Geschwindigkeit mm/s, Zeit ms, Strecke cm, Höhe cm, Steigung 1/10000). */
data class RtData(
    val speed: Long? = null, val avgSpeed: Long? = null, val ridingTime: Long? = null, val distance: Long? = null,
    val cadence: Int? = null, val heartRate: Int? = null, val avgHeartRate: Int? = null, val power: Int? = null,
    val totalAscent: Int? = null, val height: Int? = null, val slope: Int? = null, val maxSpeed: Long? = null,
    val maxHeartRate: Int? = null, val avgCadence: Int? = null, val maxCadence: Int? = null,
    val avgPower: Int? = null, val maxPower: Int? = null,
)

/** `dev_status_msg` (Dienst 13): Fahrstatus, Position, Livewerte, Navigation. */
data class DevStatusMsg(
    /** 0 nicht am Fahren, 1 fährt, 2 pausiert. */
    val cyclingStatus: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rt: RtData? = null,
    /** 0 aus, 1 an. */
    val naviStatus: Int? = null,
) {
    companion object {
        fun requestGet(): ByteArray = ProtoWriter().apply {
            varint(1, Service.DEV_STATUS.toLong())
            varint(2, DevStatusOp.GET.toLong())
        }.out.toByteArray()

        fun decode(b: ByteArray): DevStatusMsg {
            val r = ProtoReader(b)
            var cycling: Int? = null; var lat: Double? = null; var lon: Double? = null; var rt: RtData? = null; var navi: Int? = null
            while (r.hasMore) {
                val tag = r.varint().toInt()
                when (tag) {
                    (3 shl 3) or 2 -> {
                        val inner = ProtoReader(r.lengthDelimited())
                        while (inner.hasMore) {
                            val t = inner.varint().toInt()
                            if (t == (1 shl 3)) cycling = inner.varint().toInt() else inner.skip(t and 7)
                        }
                    }
                    (4 shl 3) or 2 -> {
                        val inner = ProtoReader(r.lengthDelimited())
                        while (inner.hasMore) {
                            val t = inner.varint().toInt()
                            when (t) {
                                (1 shl 3) or 1 -> lat = java.lang.Double.longBitsToDouble(inner.fixed64())
                                (2 shl 3) or 1 -> lon = java.lang.Double.longBitsToDouble(inner.fixed64())
                                else -> inner.skip(t and 7)
                            }
                        }
                    }
                    (5 shl 3) or 2 -> rt = decodeRt(r.lengthDelimited())
                    (7 shl 3) -> navi = r.varint().toInt()
                    else -> r.skip(tag and 7)
                }
            }
            return DevStatusMsg(cycling, lat, lon, rt, navi)
        }

        private fun decodeRt(b: ByteArray): RtData {
            val r = ProtoReader(b)
            val v = HashMap<Int, Long>()
            while (r.hasMore) {
                val t = r.varint().toInt()
                if (t and 7 == 0) v[t ushr 3] = r.varint() else r.skip(t and 7)
            }
            fun i(f: Int) = v[f]?.toInt()
            return RtData(
                speed = v[1], avgSpeed = v[2], ridingTime = v[3], distance = v[4], cadence = i(5), heartRate = i(6), avgHeartRate = i(7),
                power = i(8), totalAscent = i(9), height = i(11), slope = i(12), maxSpeed = v[13], maxHeartRate = i(14),
                avgCadence = i(15), maxCadence = i(16), avgPower = i(17), maxPower = i(18),
            )
        }
    }
}
