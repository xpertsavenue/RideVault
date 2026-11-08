package io.github.xpertsavenue.ridevault.driver.bikevee

import io.github.xpertsavenue.ridevault.device.Backlight
import io.github.xpertsavenue.ridevault.device.BikeComputerDriver
import io.github.xpertsavenue.ridevault.device.BikeComputerSession
import io.github.xpertsavenue.ridevault.device.Capability
import io.github.xpertsavenue.ridevault.device.DeviceInfo
import io.github.xpertsavenue.ridevault.device.DeviceModel
import io.github.xpertsavenue.ridevault.device.DeviceRef
import io.github.xpertsavenue.ridevault.device.DeviceSetting
import io.github.xpertsavenue.ridevault.device.GattChannel
import io.github.xpertsavenue.ridevault.device.GattProfile
import io.github.xpertsavenue.ridevault.device.ScanInfo
import io.github.xpertsavenue.ridevault.device.SyncEvent
import io.github.xpertsavenue.ridevault.model.RideKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BikeveeDriver : BikeComputerDriver {
    override val id = "bikevee"
    override val displayName = "BIKEVEE / BKVTOP / WEST BIKING / KROSS"

    // Service enthält „0201“, Notify „0202“, Write „0203“ (Original: util/ble.js). MTU 512 wie das Original.
    override val gattProfile = GattProfile(
        serviceContains = "0201", notifyContains = "0202", writeContains = "0203", preferredMtu = 512,
    )

    override fun identify(scan: ScanInfo): DeviceModel? {
        val name = scan.name ?: return null
        val model = MODELS.firstOrNull { name.contains(it, ignoreCase = true) } ?: return null
        return DeviceModel("BIKEVEE", model)
    }

    override fun open(device: DeviceRef, channel: GattChannel): BikeComputerSession =
        BikeveeSession(device, channel)

    companion object {
        /** BLE-Namensbestandteile der bekannten Modelle (BKVTOP100 enthält BKV… nicht, daher separat). */
        val MODELS = listOf(
            "BKVTOP100", "BKVTOP120", "BKVTOP500", "BKVTOP510", "BKVTOP520", "BKVTOP530",
            "BKV100", "BKV120", "BKV300", "BKV500", "BKV510", "BKV520", "BKV530",
            "KRC-439GPS", "west_biking",
        )
    }
}

class BikeveeSession(
    private val device: DeviceRef,
    private val channel: GattChannel,
    private val responseTimeoutMs: Long = 5_000,
    private val frameTimeoutMs: Long = 15_000,
) : BikeComputerSession {

    override val capabilities: Set<Capability> = setOf(
        Capability.READ_BATTERY, Capability.READ_FIRMWARE, Capability.SYNC_RIDES,
        Capability.SET_TIME, Capability.SET_TIME_ZONE, Capability.SET_UNITS, Capability.SET_LANGUAGE,
        Capability.SET_BACKLIGHT, Capability.SET_TIMED_BACKLIGHT, Capability.SET_AUTO_STANDBY,
        Capability.SET_USER_PROFILE,
    )

    private val frames: Flow<Frame> get() = channel.notifications.mapNotNull { Frame.parse(it) }

    /** Schickt [frame] und wartet auf die erste Antwort mit [expect]. Abo startet vor dem Schreiben. */
    private suspend fun request(frame: Frame, expect: Int = frame.command): Frame = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) { frames.first { it.command == expect } }
        try {
            channel.write(frame.bytes)
            withTimeout(responseTimeoutMs) { reply.await() }
        } finally {
            reply.cancel()
        }
    }

    override suspend fun deviceInfo(): DeviceInfo {
        val battery = BikeveeParser.parse(request(BikeveeCommands.readBattery())) as? Response.Battery
        val version = BikeveeParser.parse(request(BikeveeCommands.readVersion())) as? Response.Version
        return DeviceInfo(
            batteryPercent = battery?.percent,
            firmware = version?.version,
            firmwareDate = version?.buildDate?.toString(),
        )
    }

    override suspend fun apply(setting: DeviceSetting) {
        val frame = when (setting) {
            is DeviceSetting.Time -> BikeveeCommands.setTime(setting)
            is DeviceSetting.TimeZone -> BikeveeCommands.setTimeZone(setting)
            is DeviceSetting.Units -> BikeveeCommands.setUnits(setting)
            is DeviceSetting.Language -> BikeveeCommands.setLanguage(setting)
            is DeviceSetting.BacklightMode -> BikeveeCommands.setBacklight(setting.backlight)
            is DeviceSetting.TimedBacklight -> BikeveeCommands.setTimedBacklight(setting)
            is DeviceSetting.AutoStandby -> BikeveeCommands.setAutoStandby(setting)
            is DeviceSetting.UserProfile -> {
                // Das Original wartet hier auf keine Antwort.
                channel.write(BikeveeCommands.setUserProfile(setting).bytes)
                return
            }
        }
        request(frame)
    }

    override fun syncRides(isKnown: suspend (RideKey) -> Boolean): Flow<SyncEvent> = flow {
        val machine = RideSyncMachine(device.address, isKnown)
        coroutineScope {
            val inbox = Channel<Frame>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { inbox.trySend(it) }
            }
            try {
                execute(machine.start())
                while (!machine.isFinished) {
                    val next = withTimeout(frameTimeoutMs) { inbox.receive() }
                    execute(machine.onFrame(next))
                }
            } finally {
                collector.cancel()
            }
        }
    }

    /** Führt Aktionen der Reihe nach aus; `emit` suspendiert, bis der Konsument die Fahrt verarbeitet hat. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<SyncEvent>.execute(actions: List<SyncAction>) {
        for (a in actions) when (a) {
            is SyncAction.Send -> channel.write(a.frame.bytes)
            is SyncAction.Emit -> emit(a.event)
        }
    }
}
