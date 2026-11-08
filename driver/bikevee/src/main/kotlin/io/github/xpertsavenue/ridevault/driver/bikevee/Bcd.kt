package io.github.xpertsavenue.ridevault.driver.bikevee

import java.time.LocalDateTime

/** BCD: Dezimalziffern als Hex-Nibbles (Dezimal 25 -> 0x25). */
internal object Bcd {
    /** Kodiert [value] in [bytes] Bytes (je zwei Ziffern), höchstwertig zuerst. */
    fun encode(value: Int, bytes: Int): ByteArray {
        require(value >= 0) { "negativ: $value" }
        val digits = value.toString().padStart(bytes * 2, '0')
        require(digits.length == bytes * 2) { "$value passt nicht in $bytes BCD-Byte(s)" }
        return ByteArray(bytes) { i ->
            ((digits[2 * i] - '0') shl 4 or (digits[2 * i + 1] - '0')).toByte()
        }
    }

    /**
     * Dekodiert ein BCD-Byte. Enthält es kein gültiges Ziffernpaar, wird der
     * rohe Byte-Wert zurückgegeben (so tolerant wie das Original, das Hex-Strings parst).
     */
    fun decode(b: Int): Int {
        val hi = (b shr 4) and 0xF
        val lo = b and 0xF
        return if (hi <= 9 && lo <= 9) hi * 10 + lo else b
    }

    /** `YY MM DD hh mm ss` (BCD, Jahr ab 2000) -> Zeitpunkt, oder null bei ungültigen Werten. */
    fun dateTime(frame: Frame, payloadIndex: Int): LocalDateTime? = runCatching {
        LocalDateTime.of(
            2000 + decode(frame.u8(payloadIndex)),
            decode(frame.u8(payloadIndex + 1)),
            decode(frame.u8(payloadIndex + 2)),
            decode(frame.u8(payloadIndex + 3)),
            decode(frame.u8(payloadIndex + 4)),
            decode(frame.u8(payloadIndex + 5)),
        )
    }.getOrNull()

    fun dateTimeBytes(t: LocalDateTime): ByteArray =
        encode(t.year % 100, 1) + encode(t.monthValue, 1) + encode(t.dayOfMonth, 1) +
            encode(t.hour, 1) + encode(t.minute, 1) + encode(t.second, 1)
}
