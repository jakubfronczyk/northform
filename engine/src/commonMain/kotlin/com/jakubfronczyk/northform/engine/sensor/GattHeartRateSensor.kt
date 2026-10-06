package com.jakubfronczyk.northform.engine.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorError
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.gatt.Gatt
import com.jakubfronczyk.northform.core.ports.BleCentral
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlin.time.Duration.Companion.seconds

/**
 * `HeartRateSensor` over any `BleCentral` (D8). The same mailbox idiom as the run session (D5):
 * every producer — radio events, user intents, listener-count changes, the 1 Hz tick — sends a
 * `SensorLink.Input` into one unbounded channel; ONE coroutine owns the `SensorLink.State`, reduces,
 * and executes the commands. No lock, no shared mutable state.
 *
 * Threading: `BleCentral.events` arrives from the platform's queue; `trySend` into the channel is
 * the hand-off. Commands call back into the transport from the owner coroutine; CoreBluetooth
 * methods are safe to call from any thread (they are dispatched to the manager's own queue).
 */
class GattHeartRateSensor(
    private val central: BleCentral,
    private val wallClock: WallClock,
    private val tickEvery: kotlin.time.Duration = 1.seconds,
) : HeartRateSensor {

    private val inbox = Channel<SensorLink.Input>(Channel.UNLIMITED)
    private val states = MutableStateFlow(SensorStateChange(SensorState.Disconnected(DisconnectReason.User), wallClock.now()))
    /** Unbounded so a slow collector can never drop a sample (samples are persisted downstream). */
    private val samples = MutableSharedFlow<HrSample>(extraBufferCapacity = Int.MAX_VALUE)
    private val discoveries = MutableSharedFlow<DiscoveredSensor>(extraBufferCapacity = 64)
    private val timeouts = MutableSharedFlow<SensorId>(extraBufferCapacity = 8)
    private val unauthorized = MutableStateFlow(false)

    private var owner: Job? = null

    /** Called once from the composition root inside `boot()` (D9 step 3), before any radio event can arrive. */
    fun start(scope: CoroutineScope) {
        check(owner == null) { "GattHeartRateSensor already started" }
        owner = scope.launch {
            launch { central.events.collect { inbox.send(SensorLink.Input.Ble(it, wallClock.now())) } }
            launch { samples.subscriptionCount.collect { inbox.send(SensorLink.Input.Listeners(it, wallClock.now())) } }
            launch { while (true) { delay(tickEvery); inbox.send(SensorLink.Input.Tick(wallClock.now())) } }
            var state = SensorLink.State()
            for (input in inbox) {
                val (next, commands) = SensorLink.reduce(state, input)
                state = next
                commands.forEach(::execute)
            }
        }
    }

    private fun execute(c: SensorLink.Command) {
        when (c) {
            is SensorLink.Command.Scan -> central.scan(c.services)
            SensorLink.Command.StopScan -> central.stopScan()
            is SensorLink.Command.Connect ->
                if (central.isKnown(c.id)) central.connect(c.id, autoReconnect = true)
                else central.scan(Gatt.servicesUsed.take(1)) // scan [0x180D]; `Discovered` for the wanted id → Connect
            is SensorLink.Command.CancelConnect -> central.cancelConnect(c.id)
            is SensorLink.Command.Discover -> central.discover(c.id, c.services)
            is SensorLink.Command.SetNotify -> central.setNotify(c.id, c.characteristic, c.on)
            is SensorLink.Command.Read -> central.read(c.id, c.characteristic)
            is SensorLink.Command.Report -> {
                // D100: the same state twice in a row is one change and keeps its first time.
                if (states.value.state != c.state) states.value = SensorStateChange(c.state, c.at)
            }
            is SensorLink.Command.Emit -> check(samples.tryEmit(c.sample)) { "unbounded sample buffer refused an emit" }
            is SensorLink.Command.Discovered -> {
                discoveries.tryEmit(DiscoveredSensor(c.id, c.name, c.rssi))
                // TODO(spike): when scanning on behalf of a Connect for an unknown id, match here and
                // send Connect(id). The spike's Compose screen connects from the discovered list instead.
            }
            is SensorLink.Command.ConnectTimedOut -> timeouts.tryEmit(c.id)
            SensorLink.Command.Unauthorized -> unauthorized.value = true
        }
    }

    // ── HeartRateSensor ─────────────────────────────────────────────────────────────────────

    override fun states(): Flow<SensorStateChange> = states.asStateFlow()

    /**
     * Live samples only; stays open through drops and Bluetooth off/on; ends on cancel; throws
     * `Unauthorized` only. Subscribing bumps `subscriptionCount`, which the mailbox turns into
     * `Listeners(n)` → HR notify on/off (the shared-subscription rule).
     */
    override fun heartRate(): Flow<HrSample> = channelFlow {
        launch { unauthorized.collect { if (it) throw SensorError.Unauthorized } }
        samples.collect { send(it) }
    }

    override fun search(): Flow<DiscoveredSensor> = callbackFlow {
        central.scan(listOf(Gatt.heartRateService))
        val job = launch { discoveries.collect { send(it) } }
        awaitClose { job.cancel(); central.stopScan() } // awaitClose is mandatory in callbackFlow
    }

    override suspend fun connect(id: SensorId) {
        inbox.send(SensorLink.Input.Connect(id, wallClock.now()))
        val connected = withTimeoutOrNull(SensorLink.connectTimeout + 1.seconds) {
            states.first { it.state is SensorState.Connected }
        }
        if (connected == null) throw SensorError.ConnectTimedOut
    }

    override suspend fun disconnect() {
        inbox.send(SensorLink.Input.Disconnect(wallClock.now()))
    }
}
