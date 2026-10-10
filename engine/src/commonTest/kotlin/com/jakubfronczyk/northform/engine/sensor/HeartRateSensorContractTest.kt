package com.jakubfronczyk.northform.engine.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.SensorError
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.gatt.Gatt
import com.jakubfronczyk.northform.core.ports.BleCentral
import com.jakubfronczyk.northform.core.ports.BleEvent
import com.jakubfronczyk.northform.core.ports.GattUuid
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The `HeartRateSensor` port's contract, proven on the real `GattHeartRateSensor` over a scripted
 * `BleCentral`. The pure link machine is an internal seam; these tests cross the port the app crosses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateSensorContractTest {
    private val start = Instant.fromEpochSeconds(1_800_000_000)
    private val polar = SensorId("3F2504E0-4F89-11D3-9A0C-0305E82C3301")
    private val other = SensorId("9B2E0B20-7E0A-4C5B-8A2E-0D3B2C4F5A61")

    /** The "dumb hands" fake: records every call, the test plays the radio's answers. */
    private class ScriptedCentral : BleCentral {
        val calls = ArrayList<String>()
        val known = HashSet<SensorId>()
        private val radio = MutableSharedFlow<BleEvent>(extraBufferCapacity = 64)
        override val events: Flow<BleEvent> = radio
        fun radio(e: BleEvent) = check(radio.tryEmit(e))
        override fun scan(services: List<GattUuid>) { calls += "scan ${services.map { it.short }}" }
        override fun stopScan() { calls += "stopScan" }
        override fun connect(id: SensorId, autoReconnect: Boolean) { calls += "connect ${id.value.take(8)}" }
        override fun cancelConnect(id: SensorId) { calls += "cancelConnect ${id.value.take(8)}" }
        override fun discover(id: SensorId, services: List<GattUuid>) { calls += "discover" }
        override fun setNotify(id: SensorId, characteristic: GattUuid, on: Boolean) { calls += "notify ${characteristic.short} $on" }
        override fun read(id: SensorId, characteristic: GattUuid) { calls += "read ${characteristic.short}" }
        override fun isKnown(id: SensorId) = id in known
    }

    private fun ready(id: SensorId) = BleEvent.Ready(id, listOf(
        BleEvent.Characteristic(Gatt.heartRateService, Gatt.heartRateMeasurement, notify = true, read = false),
    ))

    private fun TestScope.sensor(central: ScriptedCentral, onPaired: (SensorId?, String?) -> Unit = { _, _ -> }): GattHeartRateSensor =
        GattHeartRateSensor(central, virtualWallClock(start), onPaired = onPaired).also { it.start(backgroundScope); runCurrent() }

    private fun ScriptedCentral.linkUp(id: SensorId) { radio(BleEvent.Connected(id)); radio(ready(id)) }

    @Test
    fun connect_to_a_known_sensor_returns_once_the_link_is_connected() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        val connecting = async { sensor.connect(polar) }
        runCurrent()
        assertTrue("connect 3F2504E0" in central.calls, "${central.calls}")
        assertFalse(connecting.isCompleted)
        central.linkUp(polar); runCurrent()
        assertTrue(connecting.isCompleted)
        assertEquals(SensorState.Connected(null), sensor.states().first().state, "states() starts with the current value")
    }

    @Test
    fun connect_to_an_unknown_sensor_scans_and_connects_when_it_is_discovered() = runTest {
        val central = ScriptedCentral() // launch reconnect on a fresh install: nothing is known yet
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        val connecting = async { sensor.connect(polar) }
        runCurrent()
        assertEquals(listOf("scan [180D]"), central.calls.filter { it.startsWith("scan") || it.startsWith("connect") })
        central.radio(BleEvent.Discovered(other, "Someone else", -70)); runCurrent()
        assertTrue(central.calls.none { it.startsWith("connect") }, "a stranger is not connected: ${central.calls}")
        central.radio(BleEvent.Discovered(polar, "Polar Sense", -60)); runCurrent()
        assertEquals(listOf("stopScan", "connect 3F2504E0"), central.calls.takeLast(2), "${central.calls}")
        central.linkUp(polar); runCurrent()
        assertTrue(connecting.isCompleted)
    }

    @Test
    fun connect_throws_after_the_links_15s_timeout_and_the_link_keeps_trying() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        val connecting = async { runCatching { sensor.connect(polar) } }
        advanceTimeBy(15.seconds); runCurrent()
        assertTrue(connecting.isCompleted, "the caller hears about the timeout when the link does, not a second later")
        assertFailsWith<SensorError.ConnectTimedOut> { connecting.await().getOrThrow() }
        advanceTimeBy(2.seconds); runCurrent() // backoff step 1 (2 s): the link re-issues the connect on its own
        assertEquals(2, central.calls.count { it == "connect 3F2504E0" }, "${central.calls}")
    }

    @Test
    fun connect_to_a_second_sensor_does_not_return_on_the_first_ones_connection() = runTest {
        val central = ScriptedCentral().apply { known += polar; known += other }
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        launch { sensor.connect(polar) }
        runCurrent(); central.linkUp(polar); runCurrent()
        assertEquals(SensorState.Connected(null), sensor.states().first().state)

        val second = async { sensor.connect(other) }
        runCurrent()
        assertFalse(second.isCompleted, "still connected to the first sensor: must wait for the second")
        central.linkUp(other); runCurrent()
        assertTrue(second.isCompleted)
    }

    @Test
    fun a_heart_rate_collector_turns_notify_on_and_receives_parsed_samples() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        launch { sensor.connect(polar) }
        runCurrent(); central.linkUp(polar); runCurrent()
        assertTrue(central.calls.none { it.startsWith("notify 2A37") }, "no listener, no notify (D9 step 4)")

        val bpm = ArrayList<Int>()
        val collector = launch { sensor.heartRate().collect { bpm += it.bpm } }
        runCurrent()
        assertTrue("notify 2A37 true" in central.calls, "${central.calls}")
        central.radio(BleEvent.Value(polar, Gatt.heartRateMeasurement, byteArrayOf(0x00, 72))); runCurrent()
        central.radio(BleEvent.Value(polar, Gatt.heartRateMeasurement, byteArrayOf(0x00, 0))); runCurrent() // 0 bpm = no contact, dropped
        assertEquals(listOf(72), bpm)
        collector.cancel(); runCurrent()
        assertTrue("notify 2A37 false" in central.calls, "last listener left → notify off")
    }

    // ── adapters/02: the facts the reliability log counts (R29) cross this port ─────────────

    /** A connected sensor with every state change recorded as it is delivered, the way the run session collects it. */
    private fun TestScope.connected(central: ScriptedCentral, seen: MutableList<SensorStateChange>): GattHeartRateSensor {
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        launch { sensor.connect(polar) }
        runCurrent(); central.linkUp(polar); runCurrent()
        backgroundScope.launch { sensor.states().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(SensorState.Connected(null)), seen.map { it.state })
        return sensor
    }

    @Test
    fun a_drop_the_system_reconnects_is_delivered_as_lost_then_connecting_then_connected_with_its_times() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val seen = ArrayList<SensorStateChange>()
        connected(central, seen)
        advanceTimeBy(10.seconds)
        central.radio(BleEvent.Disconnected(polar, isReconnecting = true, message = "The connection has timed out unexpectedly.")); runCurrent()
        assertEquals(
            listOf(SensorState.Connected(null), SensorState.Disconnected(DisconnectReason.Lost), SensorState.Connecting),
            seen.map { it.state },
            "Lost must reach the collector even though Connecting follows in the same reduce: it is the drop the log counts",
        )
        assertEquals(start + 10.seconds, seen[1].at, "the drop is stamped when the radio reported it")
        assertEquals(1, central.calls.count { it.startsWith("connect") }, "the system re-links by itself; no second connect: ${central.calls}")
        advanceTimeBy(30.seconds)
        central.linkUp(polar); runCurrent()
        assertEquals(SensorState.Connected(null), seen.last().state)
        assertEquals(30.seconds, seen.last().at - seen[1].at, "reconnect time = Connected.at − Lost.at")
    }

    @Test
    fun a_final_drop_is_lost_and_the_link_reissues_the_connect_itself() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val seen = ArrayList<SensorStateChange>()
        connected(central, seen)
        central.radio(BleEvent.Disconnected(polar, isReconnecting = false, message = null)); runCurrent()
        assertEquals(listOf(SensorState.Disconnected(DisconnectReason.Lost), SensorState.Connecting), seen.drop(1).map { it.state })
        assertEquals(2, central.calls.count { it.startsWith("connect") }, "${central.calls}")
    }

    @Test
    fun a_user_disconnect_is_not_a_drop() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val seen = ArrayList<SensorStateChange>()
        val sensor = connected(central, seen)
        sensor.disconnect(); runCurrent()
        assertEquals(listOf(SensorState.Disconnected(DisconnectReason.User)), seen.drop(1).map { it.state })
        central.radio(BleEvent.Disconnected(polar, isReconnecting = false, message = null)); runCurrent()
        assertEquals(1, seen.size - 1, "the radio's own disconnect after ours adds nothing: ${seen.map { it.state }}")
    }

    @Test
    fun bluetooth_off_is_reported_apart_from_a_drop_and_power_on_relinks() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val seen = ArrayList<SensorStateChange>()
        connected(central, seen)
        central.radio(BleEvent.PowerOff); runCurrent()
        central.radio(BleEvent.Disconnected(polar, isReconnecting = false, message = "powered off")); runCurrent()
        assertEquals(listOf(SensorState.BluetoothOff), seen.drop(1).map { it.state }, "no Lost while the radio is off (D120 of the Swift build)")
        central.radio(BleEvent.PowerOn); runCurrent()
        central.linkUp(polar); runCurrent()
        assertEquals(listOf(SensorState.BluetoothOff, SensorState.Connecting, SensorState.Connected(null)), seen.drop(1).map { it.state })
    }

    // ── SU3 pairing through the port (adapters/01) ───────────────────────────────────────────

    @Test
    fun the_chosen_sensor_is_remembered_when_connect_is_called_even_if_it_times_out() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val paired = ArrayList<SensorId?>()
        val sensor = sensor(central) { id, _ -> paired += id }
        central.radio(BleEvent.PowerOn)
        val connecting = async { runCatching { sensor.connect(polar) } }
        runCurrent()
        assertEquals(listOf<SensorId?>(polar), paired, "remembered at the moment of choosing")
        advanceTimeBy(16.seconds); runCurrent()
        assertTrue(connecting.await().isFailure)
        assertEquals(polar, sensor.linkState.value.paired, "a timeout does not unpair")
    }

    @Test
    fun forget_stops_the_link_and_clears_the_pairing() = runTest {
        val central = ScriptedCentral().apply { known += polar }
        val paired = ArrayList<SensorId?>()
        val sensor = sensor(central) { id, _ -> paired += id }
        central.radio(BleEvent.PowerOn)
        launch { sensor.connect(polar) }
        runCurrent(); central.linkUp(polar); runCurrent()
        sensor.forget(); runCurrent()
        assertEquals(listOf(polar, null), paired)
        assertEquals("cancelConnect 3F2504E0", central.calls.last { !it.startsWith("notify") })
        assertEquals(SensorState.Disconnected(DisconnectReason.User), sensor.states().first().state)
    }

    @Test
    fun search_lists_only_polar_sensors_and_never_connects_one_by_itself() = runTest {
        val central = ScriptedCentral()
        val sensor = sensor(central)
        central.radio(BleEvent.PowerOn)
        val seen = ArrayList<String?>()
        val searching = launch { sensor.search().collect { seen += it.name } }
        runCurrent()
        central.radio(BleEvent.Discovered(other, "Garmin HRM-Pro", -55))
        central.radio(BleEvent.Discovered(polar, "Polar Sense ABC123", -60)); runCurrent()
        assertEquals(listOf<String?>("Polar Sense ABC123"), seen)
        assertTrue(central.calls.none { it.startsWith("connect") }, "nothing auto-connects: ${central.calls}")
        searching.cancel()
    }
}
