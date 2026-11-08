package io.github.xpertsavenue.ridevault.driver.bikevee

import io.github.xpertsavenue.ridevault.device.BikeComputerDriver
import io.github.xpertsavenue.ridevault.device.DeviceModel
import io.github.xpertsavenue.ridevault.device.DeviceRef
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.GattChannel
import io.github.xpertsavenue.ridevault.device.ScanInfo
import io.github.xpertsavenue.ridevault.device.SyncEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun u32(v: Long) = intArrayOf((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())
private fun u16(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
private fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL)

private fun header(gps: Long, samples: Long, rides: Int) =
    Frame.of(Cmd.RIDE_REQUEST, 0, 0x25, 0x11, 0x06, 0x20, 0x15, 0x30, *u32(gps), *u32(samples), rides)

private fun gps(lat: Float, lon: Float) =
    Frame.of(Cmd.GPS_RECORD, 0x25, 0x11, 0x06, 0x20, 0x15, 0x31, 0, *f32(lat), 0, *f32(lon))

private fun sample() = Frame.of(
    Cmd.SAMPLE_RECORD,
    *u16(215), *u16(253), *u16(-15 and 0xFFFF), *u16(520), *u16(12), 140, 85, *u16(210),
)

private val START = LocalDateTime.of(2025, 11, 6, 20, 15, 30)

class ParserTest {
    @Test
    fun `Akku und Version`() {
        assertEquals(Response.Battery(3, 60), BikeveeParser.parse(Frame.of(Cmd.BATTERY, 3)))
        assertEquals(
            Response.Version("1.2.3.4", LocalDate.of(2025, 6, 15)),
            BikeveeParser.parse(Frame.of(Cmd.VERSION, 0x01, 0x02, 0x03, 0x04, 0x25, 0x06, 0x15)),
        )
    }

    @Test
    fun `Fahrtkopf`() {
        val h = assertIs<Response.RideHeader>(BikeveeParser.parse(header(gps = 1000, samples = 70000, rides = 2)))
        assertEquals(START, h.startTime)
        assertEquals(1000, h.gpsCount)
        assertEquals(70000, h.sampleCount)
        assertEquals(2, h.rideCount)
        assertEquals(71000, h.totalRecords)
    }

    @Test
    fun `GPS-Satz mit float32 little endian`() {
        val g = assertIs<Response.Gps>(BikeveeParser.parse(gps(48.0f, 11.5f)))
        assertEquals(LocalDateTime.of(2025, 11, 6, 20, 15, 31), g.time)
        assertEquals(48.0, g.latitude)
        assertEquals(11.5, g.longitude)
    }

    @Test
    fun `Messpunkt mit vorzeichenbehafteter Steigung`() {
        val s = assertIs<Response.SampleRecord>(BikeveeParser.parse(sample()))
        assertEquals(21.5, s.temperatureC)
        assertEquals(25.3, s.speedKmh)
        assertEquals(-1.5, s.gradientPercent)
        assertEquals(520, s.altitudeM)
        assertEquals(1.2, s.distanceKm)
        assertEquals(140, s.heartRate)
        assertEquals(85, s.cadenceRpm)
        assertEquals(210, s.powerW)
    }

    @Test
    fun `Distanz metrisch und imperial`() {
        val metric = assertIs<Response.Metric>(BikeveeParser.parse(Frame.of(Cmd.SUMMARY_DISTANCE, *u32(1234), 0)))
        assertEquals(123.4, metric.a)
        val imperial = assertIs<Response.Metric>(BikeveeParser.parse(Frame.of(Cmd.SUMMARY_DISTANCE, *u32(100), 1)))
        assertEquals(16.09344, imperial.a, 1e-9)
    }

    @Test
    fun `negative Temperatur`() {
        val m = assertIs<Response.Metric>(BikeveeParser.parse(Frame.of(Cmd.SUMMARY_TEMPERATURE, *u16(-5 and 0xFFFF), 0)))
        assertEquals(-5.0, m.a)
    }

    @Test
    fun `unbekannter Befehl`() {
        assertIs<Response.Unknown>(BikeveeParser.parse(Frame.of(0x77)))
    }
}

class RideSyncMachineTest {

    private fun sends(a: List<SyncAction>) = a.filterIsInstance<SyncAction.Send>().map { it.frame.command }
    private fun events(a: List<SyncAction>) = a.filterIsInstance<SyncAction.Emit>().map { it.event }

    @Test
    fun `komplette Fahrt wird gesammelt und erst nach dem Speichern bestaetigt`() = runBlocking {
        val m = RideSyncMachine("dev") { false }
        assertEquals(listOf(Cmd.RIDE_REQUEST), sends(m.start()))

        events(m.onFrame(header(gps = 1, samples = 1, rides = 1))).let {
            assertEquals(SyncEvent.Started(1), it[0])
            assertEquals(SyncEvent.Progress(0, 2), it[1])
        }
        assertEquals(listOf(Cmd.GPS_RECORD), sends(m.onFrame(gps(48.0f, 11.5f))))
        assertEquals(listOf(Cmd.SAMPLE_RECORD), sends(m.onFrame(sample())))
        assertEquals(listOf(Cmd.SUMMARY_DURATION), sends(m.onFrame(Frame.of(Cmd.SUMMARY_DURATION, 1, 2, 3))))
        assertEquals(listOf(Cmd.SUMMARY_DISTANCE), sends(m.onFrame(Frame.of(Cmd.SUMMARY_DISTANCE, *u32(1234), 0))))
        // 0x19/0x1A/0x1C/0x1D werden wie im Original nicht bestaetigt
        assertTrue(m.onFrame(Frame.of(Cmd.SUMMARY_HEART_RATE, 130, 175)).isEmpty())
        assertTrue(m.onFrame(Frame.of(Cmd.SUMMARY_CALORIES, *u16(850))).isEmpty())

        val end = m.onFrame(Frame.of(Cmd.RIDE_END))
        // Reihenfolge: erst Emit(RideReceived), dann Send(ack 17)
        val emitAt = end.indexOfFirst { it is SyncAction.Emit && it.event is SyncEvent.RideReceived }
        val ackAt = end.indexOfFirst { it is SyncAction.Send && it.frame.command == Cmd.RIDE_END }
        assertTrue(emitAt in 0 until ackAt, "Speichern muss vor der Bestaetigung liegen: $end")

        val ride = (end[emitAt] as SyncAction.Emit).event.let { assertIs<SyncEvent.RideReceived>(it).ride }
        assertEquals(START, ride.startTime)
        assertEquals(1, ride.track.size)
        assertEquals(1, ride.samples.size)
        assertEquals(3723, ride.summary.durationSeconds)
        assertEquals(123.4, ride.summary.distanceKm)
        assertEquals(130, ride.summary.avgHeartRate)
        assertEquals(175, ride.summary.maxHeartRate)
        assertEquals(850, ride.summary.calories)
        assertTrue(m.isFinished)
        assertEquals(SyncEvent.Finished, events(end).last())
    }

    @Test
    fun `bekannte Fahrt wird uebersprungen ohne zu bestaetigen`() = runBlocking {
        val m = RideSyncMachine("dev") { true }
        val out = m.onFrame(header(1, 1, 1))
        assertEquals(listOf(SyncEvent.Started(1), SyncEvent.RideSkipped(START), SyncEvent.Finished), events(out))
        assertTrue(sends(out).isEmpty())
        assertTrue(m.isFinished)
    }

    @Test
    fun `keine Fahrten`() = runBlocking {
        val m = RideSyncMachine("dev") { false }
        assertEquals(listOf(SyncEvent.Started(0), SyncEvent.Finished), events(m.onFrame(header(0, 0, 0))))
        assertTrue(m.isFinished)
    }

    @Test
    fun `mehrere Fahrten fordern die naechste an`() = runBlocking {
        val m = RideSyncMachine("dev") { false }
        m.onFrame(header(0, 0, 2))
        val end = m.onFrame(Frame.of(Cmd.RIDE_END))
        assertEquals(listOf(Cmd.RIDE_END, Cmd.RIDE_REQUEST), sends(end))
        assertTrue(!m.isFinished)
    }

    @Test
    fun `Datensaetze vor dem Kopf werden ignoriert`() = runBlocking {
        val m = RideSyncMachine("dev") { false }
        assertTrue(m.onFrame(sample()).isEmpty())
    }
}

/** Fake-Verbindung: schreibt mit, und antwortet wie ein Gerät über ein Skript. */
private class FakeChannel(private val script: suspend FakeChannel.(Frame) -> Unit) : GattChannel {
    val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    val written = mutableListOf<Frame>()
    override val notifications get() = flow
    override suspend fun write(data: ByteArray) {
        val f = Frame.parse(data)!!
        written += f
        script(f)
    }
    suspend fun reply(f: Frame) = flow.emit(f.bytes)
}

class SessionTest {
    private val device = DeviceRef("AA:BB", "BKV500-1234", DeviceModel("BIKEVEE", "BKV500"))

    @Test
    fun `Treiber erkennt Modelle am Namen`() {
        val d: BikeComputerDriver = BikeveeDriver()
        assertEquals("BKV500", d.identify(ScanInfo("BKV500-1A2B", "x"))?.name)
        assertEquals("BKVTOP120", d.identify(ScanInfo("BKVTOP120_9F", "x"))?.name)
        assertEquals("KRC-439GPS", d.identify(ScanInfo("KRC-439GPS-77", "x"))?.name)
        assertNull(d.identify(ScanInfo("Garmin Edge", "x")))
        assertNull(d.identify(ScanInfo(null, "x")))
    }

    @Test
    fun `deviceInfo liest Akku und Version`() = runBlocking {
        val ch = FakeChannel { f ->
            when (f.command) {
                Cmd.BATTERY -> reply(Frame.of(Cmd.BATTERY, 4))
                Cmd.VERSION -> reply(Frame.of(Cmd.VERSION, 0x01, 0x02, 0x03, 0x04, 0x25, 0x06, 0x15))
            }
        }
        val info = BikeveeSession(device, ch).deviceInfo()
        assertEquals(80, info.batteryPercent)
        assertEquals("1.2.3.4", info.firmware)
        assertEquals("2025-06-15", info.firmwareDate)
    }

    @Test
    fun `Einstellung wartet auf Bestaetigung des Geraets`() = runBlocking {
        val ch = FakeChannel { f -> reply(Frame.of(f.command, 0)) }
        BikeveeSession(device, ch).apply(DeviceSetting.Language(2))
        assertEquals(listOf(Cmd.LANGUAGE), ch.written.map { it.command })
    }

    @Test
    fun `komplette Synchronisation ueber die Session`() = runBlocking {
        val ch = FakeChannel { f ->
            if (f.command == Cmd.RIDE_REQUEST) {
                reply(header(gps = 1, samples = 1, rides = 1))
                reply(gps(48.0f, 11.5f))
                reply(sample())
                reply(Frame.of(Cmd.SUMMARY_DURATION, 0, 30, 0))
                reply(Frame.of(Cmd.RIDE_END))
            }
        }
        val events = BikeveeSession(device, ch).syncRides { false }.toList()

        assertEquals(
            listOf("Started", "Progress", "Progress", "Progress", "RideReceived", "Finished"),
            events.map { it::class.simpleName },
        )
        val ride = events.filterIsInstance<SyncEvent.RideReceived>().single().ride
        assertEquals(1800, ride.summary.durationSeconds)
        assertEquals(
            listOf(Cmd.RIDE_REQUEST, Cmd.GPS_RECORD, Cmd.SAMPLE_RECORD, Cmd.SUMMARY_DURATION, Cmd.RIDE_END),
            ch.written.map { it.command },
        )
    }
}
