package com.jakubfronczyk.northform.engine.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.gatt.BatteryLevel
import com.jakubfronczyk.northform.core.gatt.DeviceInformation
import com.jakubfronczyk.northform.core.gatt.Gatt
import com.jakubfronczyk.northform.core.gatt.HeartRateMeasurement
import com.jakubfronczyk.northform.core.ports.BleEvent
import com.jakubfronczyk.northform.core.ports.GattUuid
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The sensor link state machine (D8): the brain behind the `BleCentral` hands. Pure — a function
 * from (state, input) to (state, commands); no coroutines, no clock reads, no platform. Ported from
 * `PolarSensor.swift` (northform-ios) with the radio decisions that used to live inside Polar's SDK
 * added: scan/connect, discovery, reconnect policy, backoff, restoration.
 *
 * Invariants:
 *  - HR-lost is NOT decided here: the run reducer sees sample gaps (5 s rule). This machine only
 *    says whether the link is up and forwards every non-zero sample.
 *  - Bluetooth off is not a drop (reported as [SensorState.BluetoothOff], no reconnect attempt;
 *    PowerOn re-arms).
 *  - A final disconnect while still wanted ⇒ re-issue connect immediately (an iOS pending connect
 *    waits for the peripheral); a failed connect ⇒ backoff 2·4·8·16·30 s.
 */
object SensorLink {

    val connectTimeout: Duration = 15.seconds
    val backoffSteps: List<Duration> = listOf(2, 4, 8, 16, 30).map { it.seconds }
    val batteryPoll: Duration = 300.seconds

    data class State(
        val wanted: SensorId? = null,
        val connected: SensorId? = null,
        val ready: Boolean = false,
        val powered: Boolean = true,
        val battery: Int? = null,
        val batteryNotifies: Boolean = false,
        val firmware: String? = null,
        val listeners: Int = 0,
        val hrNotifying: Boolean = false,
        /** Index into [backoffSteps]; reset on connect. */
        val attempt: Int = 0,
        val connectRequestedAt: Instant? = null,
        val lastBatteryReadAt: Instant? = null,
        val reported: SensorState = SensorState.Disconnected(DisconnectReason.User),
    )

    sealed interface Input {
        data class Ble(val event: BleEvent, val at: Instant) : Input
        data class Connect(val id: SensorId, val at: Instant) : Input
        data class Disconnect(val at: Instant) : Input
        /** Collectors of `heartRate()` came or went. */
        data class Listeners(val count: Int, val at: Instant) : Input
        /** A 1 Hz heartbeat; drives the connect timeout, backoff and battery polling. */
        data class Tick(val at: Instant) : Input
    }

    sealed interface Command {
        data class Scan(val services: List<GattUuid>) : Command
        data object StopScan : Command
        data class Connect(val id: SensorId) : Command
        data class CancelConnect(val id: SensorId) : Command
        data class Discover(val id: SensorId, val services: List<GattUuid>) : Command
        data class SetNotify(val id: SensorId, val characteristic: GattUuid, val on: Boolean) : Command
        data class Read(val id: SensorId, val characteristic: GattUuid) : Command
        data class Report(val state: SensorState, val at: Instant) : Command
        data class Emit(val sample: HrSample) : Command
        data class Discovered(val id: SensorId, val name: String?, val rssi: Int) : Command
        /** The connect attempt timed out (15 s); `connect()` callers throw. */
        data class ConnectTimedOut(val id: SensorId) : Command
        data object Unauthorized : Command
    }

    data class Result(val state: State, val commands: List<Command>)

    fun reduce(s: State, input: Input): Result = when (input) {
        is Input.Connect -> connect(s, input.id, input.at)
        is Input.Disconnect -> disconnect(s, input.at)
        is Input.Listeners -> reconcileNotify(s.copy(listeners = input.count))
        is Input.Tick -> tick(s, input.at)
        is Input.Ble -> ble(s, input.event, input.at)
    }

    // ── user intents ────────────────────────────────────────────────────────────────────────

    private fun connect(s: State, id: SensorId, at: Instant): Result {
        if (s.connected == id) return Result(s, emptyList())
        val next = s.copy(wanted = id, attempt = 0, connectRequestedAt = at)
        val cmds = buildList {
            if (s.powered) add(report(SensorState.Connecting, at))
            // The transport decides scan-vs-direct: `isKnown` is answered by the adapter when it
            // executes Connect (iOS: retrievePeripherals(withIdentifiers:) → connect; else scan).
            add(Command.Connect(id))
        }
        return Result(next.reported(cmds), cmds)
    }

    private fun disconnect(s: State, at: Instant): Result {
        val id = s.wanted ?: s.connected ?: return Result(s, emptyList())
        val cmds = listOf(
            Command.StopScan,
            Command.CancelConnect(id),
            report(SensorState.Disconnected(DisconnectReason.User), at),
        )
        return Result(State(powered = s.powered).reported(cmds), cmds)
    }

    // ── radio events ────────────────────────────────────────────────────────────────────────

    private fun ble(s: State, e: BleEvent, at: Instant): Result = when (e) {
        BleEvent.PowerOn -> {
            val next = s.copy(powered = true)
            val cmds = when {
                s.connected != null -> listOf(report(SensorState.Connected(s.battery), at))
                s.wanted != null -> listOf(report(SensorState.Connecting, at), Command.Connect(s.wanted))
                else -> listOf(report(SensorState.Disconnected(DisconnectReason.User), at))
            }
            val rearmed = if (s.connected == null && s.wanted != null) next.copy(attempt = 0, connectRequestedAt = at) else next
            Result(rearmed.reported(cmds), cmds)
        }
        BleEvent.PowerOff -> {
            val cmds = listOf(report(SensorState.BluetoothOff, at))
            Result(s.dropped().copy(powered = false).reported(cmds), cmds)
        }
        BleEvent.Unauthorized -> {
            val cmds = listOf(report(SensorState.Unauthorized, at), Command.Unauthorized)
            Result(s.dropped().reported(cmds), cmds)
        }
        is BleEvent.Restored -> {
            // D9 step 4: adopt the peripheral iOS handed back; do NOT turn HR on until a listener appears.
            val p = e.peripherals.firstOrNull()
            if (p == null || s.wanted != null) Result(s, emptyList())
            else {
                val next = s.copy(wanted = p.id, connected = p.id.takeIf { p.connected }, connectRequestedAt = at)
                val cmds = if (p.connected) listOf(report(SensorState.Connecting, at), Command.Discover(p.id, Gatt.servicesUsed))
                           else listOf(report(SensorState.Connecting, at), Command.Connect(p.id))
                Result(next.reported(cmds), cmds)
            }
        }
        is BleEvent.Discovered -> Result(s, listOf(Command.Discovered(e.id, e.name, e.rssi)))
        is BleEvent.Connected -> {
            if (e.id != s.wanted) Result(s, emptyList())
            else Result(
                s.copy(connected = e.id, attempt = 0, connectRequestedAt = null, ready = false),
                listOf(Command.StopScan, Command.Discover(e.id, Gatt.servicesUsed)),
            )
        }
        is BleEvent.FailedToConnect -> {
            if (e.id != s.wanted) Result(s, emptyList())
            else Result(s.copy(attempt = minOf(s.attempt + 1, backoffSteps.size), connectRequestedAt = at), emptyList())
            // The retry fires from `tick` once backoffSteps[attempt] has elapsed since connectRequestedAt.
        }
        is BleEvent.Disconnected -> {
            if (e.id != s.connected && e.id != s.wanted) Result(s, emptyList())
            else {
                val lost = s.wanted == e.id
                val cmds = buildList {
                    if (s.powered) add(report(if (lost) SensorState.Disconnected(DisconnectReason.Lost) else SensorState.Disconnected(DisconnectReason.User), at))
                    if (lost && s.powered) {
                        add(report(SensorState.Connecting, at))
                        // isReconnecting = true → the system re-links (CBConnectPeripheralOptionEnableAutoReconnect).
                        // false → a final disconnect: re-issue a pending connect ourselves.
                        if (!e.isReconnecting) add(Command.Connect(e.id))
                    }
                }
                Result(s.dropped().copy(connectRequestedAt = at).reported(cmds), cmds)
            }
        }
        is BleEvent.Ready -> {
            if (e.id != s.connected) Result(s, emptyList())
            else {
                val battNotify = e.characteristics.any { it.uuid == Gatt.batteryLevel && it.notify }
                val next = s.copy(ready = true, batteryNotifies = battNotify, lastBatteryReadAt = at)
                val cmds = buildList {
                    add(Command.Read(e.id, Gatt.batteryLevel))
                    add(Command.Read(e.id, Gatt.firmwareRevision))
                    if (battNotify) add(Command.SetNotify(e.id, Gatt.batteryLevel, true))
                    add(report(SensorState.Connected(s.battery), at))
                }
                reconcileNotify(next.reported(cmds), cmds)
            }
        }
        is BleEvent.Value -> value(s, e, at)
        is BleEvent.ReadFailed -> Result(s, emptyList())
    }

    private fun value(s: State, e: BleEvent.Value, at: Instant): Result = when {
        e.id != s.connected -> Result(s, emptyList())
        e.characteristic == Gatt.heartRateMeasurement -> {
            val m = HeartRateMeasurement.parse(e.bytes)
            // 0 = "no reading" on Polar devices; <30/>230 is the run reducer's rule (D119), not ours.
            if (m == null || m.bpm == 0) Result(s, emptyList())
            else Result(s, listOf(Command.Emit(HrSample(at, m.bpm))))
        }
        e.characteristic == Gatt.batteryLevel -> {
            val level = BatteryLevel.parse(e.bytes)
            if (level == null || level == s.battery) Result(s.copy(lastBatteryReadAt = at), emptyList())
            else {
                val cmds = listOf(report(SensorState.Connected(level), at))
                Result(s.copy(battery = level, lastBatteryReadAt = at).reported(cmds), cmds)
            }
        }
        e.characteristic == Gatt.firmwareRevision -> Result(s.copy(firmware = DeviceInformation.parseString(e.bytes)), emptyList())
        else -> Result(s, emptyList())
    }

    // ── time ────────────────────────────────────────────────────────────────────────────────

    private fun tick(s: State, at: Instant): Result {
        val wanted = s.wanted ?: return Result(s, emptyList())
        val cmds = mutableListOf<Command>()
        var next = s
        if (s.connected == null && s.powered) {
            val since = s.connectRequestedAt?.let { at - it }
            if (since != null) {
                // First attempt: the 15 s connect timeout surfaces to `connect()` callers. The link keeps trying.
                if (s.attempt == 0 && since >= connectTimeout) {
                    cmds += Command.ConnectTimedOut(wanted)
                    next = next.copy(connectRequestedAt = at, attempt = 1)
                } else if (s.attempt > 0 && since >= backoffSteps[minOf(s.attempt, backoffSteps.size) - 1]) {
                    cmds += Command.Connect(wanted)
                    next = next.copy(connectRequestedAt = at)
                }
            }
        }
        if (s.connected != null && s.ready && !s.batteryNotifies) {
            val last = s.lastBatteryReadAt
            if (last == null || at - last >= batteryPoll) {
                cmds += Command.Read(s.connected, Gatt.batteryLevel)
                next = next.copy(lastBatteryReadAt = at)
            }
        }
        return Result(next, cmds)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    /** HR notify runs exactly when someone listens and the link is ready (the Swift `reconcileStreaming`). */
    private fun reconcileNotify(s: State, prefix: List<Command> = emptyList()): Result {
        val id = s.connected
        val shouldRun = s.listeners > 0 && id != null && s.ready
        return when {
            shouldRun && !s.hrNotifying && id != null ->
                Result(s.copy(hrNotifying = true), prefix + Command.SetNotify(id, Gatt.heartRateMeasurement, true))
            !shouldRun && s.hrNotifying && id != null ->
                Result(s.copy(hrNotifying = false), prefix + Command.SetNotify(id, Gatt.heartRateMeasurement, false))
            else -> Result(s, prefix)
        }
    }

    private fun State.dropped() = copy(connected = null, ready = false, hrNotifying = false, batteryNotifies = false)

    private fun report(state: SensorState, at: Instant) = Command.Report(state, at)

    /** Remember the last reported state so repeats can be collapsed by the sensor (D100: same state twice = one change). */
    private fun State.reported(cmds: List<Command>): State =
        cmds.filterIsInstance<Command.Report>().lastOrNull()?.let { copy(reported = it.state) } ?: this
}
