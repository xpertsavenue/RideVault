package io.github.xpertsavenue.ridevault.driver.bikevee

/**
 * Ein 20-Byte-Rahmen des Bikevee-Hauptprotokolls: `55 AA <cmd> <16 Byte Payload> <XOR>`.
 * Siehe docs/bikevee-protokoll.md.
 */
class Frame private constructor(val bytes: ByteArray) {
    val command: Int get() = bytes[2].toInt() and 0xFF

    /** Die 16 Nutzbytes (Byte 3..18). */
    val payload: ByteArray get() = bytes.copyOfRange(3, 19)

    val checksum: Int get() = bytes[19].toInt() and 0xFF

    /** Das Original ignoriert die Prüfsumme beim Empfang; wir melden sie nur. */
    val checksumValid: Boolean get() = checksum == xor(bytes, 0, 19)

    /** Unsigned-Byte der Payload. */
    fun u8(payloadIndex: Int): Int = bytes[3 + payloadIndex].toInt() and 0xFF

    fun u16le(payloadIndex: Int): Int = u8(payloadIndex) or (u8(payloadIndex + 1) shl 8)

    fun i16le(payloadIndex: Int): Int = u16le(payloadIndex).toShort().toInt()

    fun u32le(payloadIndex: Int): Long =
        u16le(payloadIndex).toLong() or (u16le(payloadIndex + 2).toLong() shl 16)

    fun f32le(payloadIndex: Int): Float = Float.fromBits(u32le(payloadIndex).toInt())

    fun toHex(): String = bytes.joinToString("") { "%02X".format(it) }

    override fun toString(): String = "Frame(${toHex()})"

    override fun equals(other: Any?): Boolean = other is Frame && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val SIZE = 20
        const val PAYLOAD_SIZE = 16

        /** Baut einen Rahmen; [payload] wird rechts mit Nullen auf 16 Byte aufgefüllt. */
        fun of(command: Int): Frame = of(command, ByteArray(0))

        fun of(command: Int, payload: ByteArray): Frame {
            require(payload.size <= PAYLOAD_SIZE) { "Payload zu lang: ${payload.size}" }
            val b = ByteArray(SIZE)
            b[0] = 0x55
            b[1] = 0xAA.toByte()
            b[2] = command.toByte()
            payload.copyInto(b, 3)
            b[19] = xor(b, 0, 19).toByte()
            return Frame(b)
        }

        fun of(command: Int, vararg payload: Int): Frame =
            of(command, ByteArray(payload.size) { payload[it].toByte() })

        /** Liefert null, wenn Länge oder Kopf nicht passen (wie die Original-App: andere Längen werden ignoriert). */
        fun parse(raw: ByteArray): Frame? {
            if (raw.size != SIZE) return null
            if (raw[0] != 0x55.toByte() || raw[1] != 0xAA.toByte()) return null
            return Frame(raw.copyOf())
        }

        private fun xor(b: ByteArray, from: Int, to: Int): Int {
            var x = 0
            for (i in from until to) x = x xor (b[i].toInt() and 0xFF)
            return x
        }
    }
}
