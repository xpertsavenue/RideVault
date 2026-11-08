package io.github.xpertsavenue.ridevault.driver.bikevee

import io.github.xpertsavenue.ridevault.device.Backlight
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.SpeedUnit

/** Befehlsnummern (Byte 2 des Rahmens). Antworten des Geräts tragen dieselbe Nummer. */
object Cmd {
    const val SET_TIME = 0x01
    const val SUMMARY_DURATION = 0x02
    const val SUMMARY_DISTANCE = 0x03
    const val SUMMARY_AVG_SPEED = 0x04
    const val SUMMARY_MAX_SPEED = 0x05
    const val SUMMARY_TEMPERATURE = 0x06
    const val SUMMARY_CLIMB = 0x07
    const val SUMMARY_ALTITUDE = 0x08
    const val SUMMARY_GRADIENT = 0x09
    const val TOTAL_DISTANCE = 0x0A
    const val TOTAL_TIME = 0x0B
    const val TOTAL_CLIMB = 0x0C
    const val BATTERY = 0x0D
    const val GPS_RECORD = 0x0E
    const val VERSION = 0x0F
    const val BACKLIGHT = 0x10
    const val TIMED_BACKLIGHT = 0x11
    const val AUTO_STANDBY = 0x12
    const val TIME_ZONE = 0x13
    const val LANGUAGE = 0x14
    const val UNITS = 0x15
    const val RIDE_REQUEST = 0x16
    const val RIDE_END = 0x17
    const val SAMPLE_RECORD = 0x18
    const val SUMMARY_HEART_RATE = 0x19
    const val SUMMARY_CADENCE = 0x1A
    const val USER_PROFILE = 0x1B
    const val SUMMARY_CALORIES = 0x1C
    const val SUMMARY_POWER = 0x1D
    const val NEW_RIDES = 0x20
}

/** Erzeugt Rahmen für alle bekannten App→Gerät-Befehle. */
object BikeveeCommands {
    fun readBattery(): Frame = Frame.of(Cmd.BATTERY)
    fun readVersion(): Frame = Frame.of(Cmd.VERSION)
    fun hasNewRides(): Frame = Frame.of(Cmd.NEW_RIDES)
    fun requestRide(): Frame = Frame.of(Cmd.RIDE_REQUEST)

    /** Bestätigung für einen empfangenen Datensatz (`55AA<cmd>00…`). */
    fun ack(command: Int): Frame = Frame.of(command)

    fun setTime(s: DeviceSetting.Time): Frame = Frame.of(
        Cmd.SET_TIME,
        Bcd.dateTimeBytes(s.local) + byteArrayOf(s.timeFormat.toByte()) + Bcd.dateTimeBytes(s.utc),
    )

    fun setBacklight(b: Backlight): Frame = Frame.of(
        Cmd.BACKLIGHT,
        when (b) {
            Backlight.Off -> byteArrayOf(0x00, 0x00, 0x00)
            Backlight.AlwaysOn -> byteArrayOf(0x01, 0x00, 0x00)
            // Sekunden als vierstelliger Dezimalwert in BCD: 30 -> 00 30, 300 -> 03 00
            is Backlight.Timeout -> byteArrayOf(0x02) + Bcd.encode(b.seconds, 2)
        },
    )

    fun setTimedBacklight(s: DeviceSetting.TimedBacklight): Frame {
        val start = s.startMinutes
        val end = s.endMinutes
        if (start == null || end == null) return Frame.of(Cmd.TIMED_BACKLIGHT)
        return Frame.of(Cmd.TIMED_BACKLIGHT, byteArrayOf(0x01) + hhmm(start) + hhmm(end))
    }

    fun setAutoStandby(s: DeviceSetting.AutoStandby): Frame =
        Frame.of(Cmd.AUTO_STANDBY, Bcd.encode(s.hours, 1) + Bcd.encode(s.minutes, 1))

    /** Zone als Stunden (BCD) + ASCII 'E' (0x45) oder 'W' (0x57). */
    fun setTimeZone(s: DeviceSetting.TimeZone): Frame =
        Frame.of(Cmd.TIME_ZONE, Bcd.encode(s.hours, 1) + byteArrayOf(if (s.east) 0x45 else 0x57))

    /** [index] 1-basiert, als BCD gesendet (1..12). */
    fun setLanguage(s: DeviceSetting.Language): Frame =
        Frame.of(Cmd.LANGUAGE, Bcd.encode(s.index, 1))

    fun setUnits(s: DeviceSetting.Units): Frame =
        Frame.of(Cmd.UNITS, if (s.speed == SpeedUnit.MPH) 1 else 0)

    /**
     * Nutzerprofil: Geschlechts-Byte, Größe (cm), Gewicht (kg), Alter – jeweils binär.
     * Welches Geschlechts-Byte für „männlich“ gilt, ist nicht verifiziert (Original: Profilwert 1 -> 0x00).
     */
    fun setUserProfile(s: DeviceSetting.UserProfile): Frame = Frame.of(
        Cmd.USER_PROFILE,
        if (s.male) 0x00 else 0x01, s.heightCm, s.weightKg, s.age,
    )

    private fun hhmm(minutesOfDay: Int): ByteArray {
        require(minutesOfDay in 0 until 24 * 60) { "Minuten seit Mitternacht: $minutesOfDay" }
        return Bcd.encode(minutesOfDay / 60, 1) + Bcd.encode(minutesOfDay % 60, 1)
    }
}
