package com.jakubfronczyk.northform.engine.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
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
    /** SU3 pairing (adapters/01): the sensor to persist as paired (id + advertised name if seen), `null` on forget. Called from the mailbox coroutine. */
    private val onPaired: (id: SensorId?, name: String?) -> Unit = { _, _ -> },
) : HeartRateSensor {

    private val inbox = Channel<SensorLink.Input>(Channel.UNLIMITED)
    /**
     * Every state change, none skipped: a drop is `Disconnected(Lost)` immediately followed by `Connecting`
     * in ONE reduce, and a `StateFlow` (conflating by design) handed collectors only the `Connecting`, so
     * the run never recorded `SensorLost` from the real sensor (adapters/02, R29). `replay = 1` keeps the
     * port's "starts with the current value"; the unbounded extra buffer keeps `tryEmit` from ever
     * dropping a change on a slow collector. [latest] is what D100's "same state twice = one change" compares against.
     */
    private val states = MutableSharedFlow<SensorStateChange>(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
    private var latest = SensorStateChange(SensorState.Disconnected(DisconnectReason.User), wallClock.now()).also { states.tryEmit(it) }
    /** Unbounded so a slow collector can never drop a sample (samples are persisted downstream). */
    private val samples = MutableSharedFlow<HrSample>(extraBufferCapacity = Int.MAX_VALUE)
    private val discoveries = MutableSharedFlow<DiscoveredSensor>(extraBufferCapacity = 64)
    /** The link's first-attempt timeout (15 s) for the id it was trying; `connect()` turns it into [SensorError.ConnectTimedOut]. */
    private val timeouts = MutableSharedFlow<SensorId>(extraBufferCapacity = 8)
    private val unauthorized = MutableStateFlow(false)
    /** The link's state after every reduce; `connect()` watches `connected` here, not the reported `SensorState`. */
    private val link = MutableStateFlow(SensorLink.State())
    /** Read-only view of the link for what the frozen port does not carry: firmware (E16), the paired id. */
    val linkState: StateFlow<SensorLink.State> = link.asStateFlow()

    private var owner: Job? = null
    /**
     * A `Connect` for an id the platform does not know yet: we scan for it and connect when it is
     * discovered. Owned by the mailbox coroutine like the link state; never touched elsewhere.
     */
    private var scanningFor: SensorId? = null

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
                commands.forEach { execute(it, state) }
                link.value = state
            }
        }
    }

    private fun execute(c: SensorLink.Command, s: SensorLink.State) {
        when (c) {
            is SensorLink.Command.Scan -> central.scan(c.services)
            SensorLink.Command.StopScan -> if (scanningFor == null) central.stopScan()
            is SensorLink.Command.Connect ->
                if (central.isKnown(c.id)) { scanningFor = null; central.connect(c.id, autoReconnect = true) }
                else { scanningFor = c.id; central.scan(Gatt.servicesUsed.take(1)) } // scan [0x180D] until it shows up
            is SensorLink.Command.CancelConnect -> { scanningFor = null; central.cancelConnect(c.id) }
            is SensorLink.Command.Discover -> central.discover(c.id, c.services)
            is SensorLink.Command.SetNotify -> central.setNotify(c.id, c.characteristic, c.on)
            is SensorLink.Command.Read -> central.read(c.id, c.characteristic)
            is SensorLink.Command.Report -> {
                // D100: the same state twice in a row is one change and keeps its first time.
                if (latest.state != c.state) {
                    latest = SensorStateChange(c.state, c.at)
                    check(states.tryEmit(latest)) { "unbounded state buffer refused an emit" }
                }
            }
            is SensorLink.Command.Emit -> check(samples.tryEmit(c.sample)) { "unbounded sample buffer refused an emit" }
            is SensorLink.Command.Discovered -> {
                discoveries.tryEmit(DiscoveredSensor(c.id, c.name, c.rssi))
                if (c.id == scanningFor) { // the sensor we were scanning for: stop (unless the UI is searching) and connect
                    scanningFor = null
                    if (!s.scanWanted) central.stopScan()
                    central.connect(c.id, autoReconnect = true)
                }
            }
            is SensorLink.Command.ConnectTimedOut -> timeouts.tryEmit(c.id)
            SensorLink.Command.Unauthorized -> unauthorized.value = true
            is SensorLink.Command.Remember -> onPaired(c.id, c.name)
            SensorLink.Command.Forget -> onPaired(null, null)
        }
    }

    // ── HeartRateSensor ─────────────────────────────────────────────────────────────────────

    override fun states(): Flow<SensorStateChange> = states

    /**
     * Live samples only; stays open through drops and Bluetooth off/on; ends on cancel; throws
     * `Unauthorized` only. Subscribing bumps `subscriptionCount`, which the mailbox turns into
     * `Listeners(n)` → HR notify on/off (the shared-subscription rule).
     */
    override fun heartRate(): Flow<HrSample> = channelFlow {
        launch { unauthorized.collect { if (it) throw SensorError.Unauthorized } }
        samples.collect { send(it) }
    }

    /** Scanning is a link decision (only once poweredOn), so it goes through the mailbox, not straight to the radio. */
    override fun search(): Flow<DiscoveredSensor> = callbackFlow {
        val job = launch { discoveries.collect { send(it) } }
        inbox.send(SensorLink.Input.Search(on = true, at = wallClock.now()))
        awaitClose { // awaitClose is mandatory in callbackFlow
            job.cancel()
            inbox.trySend(SensorLink.Input.Search(on = false, at = wallClock.now()))
        }
    }

    /**
     * Returns once the link is connected to **this** id (a link already on another sensor does not
     * count), throws [SensorError.ConnectTimedOut] when the link's first 15 s attempt times out. The
     * link keeps trying after the throw (the port's contract). The intent is sent from `onStart`, so the
     * watch exists before the mailbox can answer (the send never suspends: the inbox is unbounded).
     */
    override suspend fun connect(id: SensorId) {
        val outcome = withTimeoutOrNull(SensorLink.connectTimeout + 1.seconds) {
            merge<Boolean>(
                link.filter { it.connected == id }.map { true },
                timeouts.filter { it == id }.map { false },
            ).onStart { inbox.send(SensorLink.Input.Connect(id, wallClock.now())) }.first()
        }
        if (outcome != true) throw SensorError.ConnectTimedOut
    }

    override suspend fun disconnect() {
        inbox.send(SensorLink.Input.Disconnect(wallClock.now()))
    }

    /** SU3 "forget": disconnects and drops the pairing (outside the frozen port, like [linkState]). */
    suspend fun forget() {
        inbox.send(SensorLink.Input.Forget(wallClock.now()))
    }
}
