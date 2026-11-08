package io.github.xpertsavenue.ridevault.driver.bikevee

import io.github.xpertsavenue.ridevault.device.Backlight
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.SpeedUnit
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Portierung der Original-Prüfsumme (XOR über Hex-Paare des Strings), nur für Werte >= 0x10 korrekt. */
private fun originalChecksum(hex: String): String {
    var x = 0
    for (i in hex.indices step 2) x = x xor hex.substring(i, i + 2).toInt(16)
    return "%02x".format(x)
}

class FrameAndCommandsTest {

    @Test
    fun `Akku-Anfrage entspricht dem Original-Hexstring`() {
        // Original: "55AA0D00000000000000000000000000000000" + XOR (55^AA^0D = F2)
        assertEquals("55AA0D00000000000000000000000000000000F2", BikeveeCommands.readBattery().toHex())
    }

    @Test
    fun `einfache Anfragen haben die erwartete Pruefsumme`() {
        assertEquals(0xF0, BikeveeCommands.readVersion().checksum)
        assertEquals(0xE9, BikeveeCommands.requestRide().checksum)
        assertEquals(0xE8, BikeveeCommands.ack(Cmd.RIDE_END).checksum)
    }

    @Test
    fun `Prueflsumme stimmt mit Original-Algorithmus ueberein`() {
        val frame = BikeveeCommands.setTime(
            DeviceSetting.Time(
                local = LocalDateTime.of(2025, 11, 6, 20, 15, 30),
                utc = LocalDateTime.of(2025, 11, 6, 19, 15, 30),
                timeFormat = 1,
            ),
        )
        val hex = frame.toHex()
        // 55 AA 01 | lokal | Format | UTC | 000000
        assertEquals("55AA01" + "251106201530" + "01" + "251106191530" + "000000", hex.substring(0, 38))
        assertEquals(originalChecksum(hex.substring(0, 38)).uppercase(), hex.substring(38))
        assertTrue(frame.checksumValid)
    }

    @Test
    fun `parse akzeptiert nur 20 Byte mit Kopf 55 AA`() {
        val ok = BikeveeCommands.readBattery().bytes
        assertNotNull(Frame.parse(ok))
        assertNull(Frame.parse(ok.copyOf(19)))
        assertNull(Frame.parse(ok.copyOf(21)))
        val bad = ok.copyOf().also { it[0] = 0x54 }
        assertNull(Frame.parse(bad))
    }

    @Test
    fun `falsche Pruefsumme wird gemeldet aber nicht abgelehnt`() {
        val b = BikeveeCommands.readBattery().bytes.copyOf().also { it[19] = 0 }
        val f = Frame.parse(b)
        assertNotNull(f)
        assertFalse(f.checksumValid)
    }

    @Test
    fun `BCD kodiert und dekodiert`() {
        assertContentEquals(byteArrayOf(0x00, 0x30), Bcd.encode(30, 2))
        assertContentEquals(byteArrayOf(0x03, 0x00), Bcd.encode(300, 2))
        assertContentEquals(byteArrayOf(0x06, 0x00), Bcd.encode(600, 2))
        assertEquals(25, Bcd.decode(0x25))
        assertEquals(0x0A, Bcd.decode(0x0A)) // ungueltige Nibbles: Rohwert
    }

    @Test
    fun `Beleuchtung`() {
        assertEquals("000000", payloadHex(BikeveeCommands.setBacklight(Backlight.Off), 3))
        assertEquals("010000", payloadHex(BikeveeCommands.setBacklight(Backlight.AlwaysOn), 3))
        assertEquals("020030", payloadHex(BikeveeCommands.setBacklight(Backlight.Timeout(30)), 3))
        assertEquals("020060", payloadHex(BikeveeCommands.setBacklight(Backlight.Timeout(60)), 3))
        assertEquals("020300", payloadHex(BikeveeCommands.setBacklight(Backlight.Timeout(300)), 3))
        assertEquals("020600", payloadHex(BikeveeCommands.setBacklight(Backlight.Timeout(600)), 3))
    }

    @Test
    fun `zeitgesteuerte Beleuchtung`() {
        val on = BikeveeCommands.setTimedBacklight(DeviceSetting.TimedBacklight(22 * 60 + 30, 6 * 60 + 15))
        assertEquals("0122300615", payloadHex(on, 5))
        val off = BikeveeCommands.setTimedBacklight(DeviceSetting.TimedBacklight(null, null))
        assertEquals("00", payloadHex(off, 1))
    }

    @Test
    fun `Standby Zeitzone Sprache Einheiten`() {
        assertEquals("0130", payloadHex(BikeveeCommands.setAutoStandby(DeviceSetting.AutoStandby(1, 30)), 2))
        assertEquals("0545", payloadHex(BikeveeCommands.setTimeZone(DeviceSetting.TimeZone(5, east = true)), 2))
        assertEquals("1057", payloadHex(BikeveeCommands.setTimeZone(DeviceSetting.TimeZone(10, east = false)), 2))
        assertEquals("02", payloadHex(BikeveeCommands.setLanguage(DeviceSetting.Language(2)), 1))
        assertEquals("12", payloadHex(BikeveeCommands.setLanguage(DeviceSetting.Language(12)), 1))
        assertEquals("00", payloadHex(BikeveeCommands.setUnits(DeviceSetting.Units(SpeedUnit.KMH)), 1))
        assertEquals("01", payloadHex(BikeveeCommands.setUnits(DeviceSetting.Units(SpeedUnit.MPH)), 1))
    }

    @Test
    fun `Nutzerprofil ist binaer kodiert`() {
        val f = BikeveeCommands.setUserProfile(DeviceSetting.UserProfile(male = true, heightCm = 180, weightKg = 75, age = 42))
        assertEquals("00B44B2A", payloadHex(f, 4))
    }

    private fun payloadHex(f: Frame, n: Int) = f.payload.take(n).joinToString("") { "%02X".format(it) }
}
