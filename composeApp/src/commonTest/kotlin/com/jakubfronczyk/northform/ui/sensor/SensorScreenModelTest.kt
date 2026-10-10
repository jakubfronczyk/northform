package com.jakubfronczyk.northform.ui.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Mode
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Signal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Sensor screen's states as a table (SU10, D72), on the fake sensor and the virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorScreenModelTest {
    private val paired = FakeSensorDetails.sample
    private val stranger = DiscoveredSensor(SensorId("9B2E0B20-7E0A-4C5B-8A2E-0D3B2C4F5A61"), "Polar H10 FFEE11", -80)

    private fun TestScope.open(sensor: FakeHeartRateSensor, details: FakeSensorDetails): SensorScreenModel {
        val model = SensorScreenModel(sensor, details)
        backgroundScope.launch { model.follow() }
        runCurrent()
        return model
    }

    @Test
    fun paired_and_not_connected_shows_disconnected_with_the_paired_identity() = runTest {
        val model = open(FakeHeartRateSensor(), FakeSensorDetails(paired))
        val ui = model.ui.value
        assertEquals(Mode.Disconnected, ui.mode)
        assertEquals("Polar Verity Sense", ui.paired?.displayName)
        assertEquals("A1B2C3D4", ui.paired?.code)
        assertNull(ui.battery)
    }

    @Test
    fun connect_shows_searching_then_connected_with_battery_and_live_heart_rate() = runTest {
        val sensor = FakeHeartRateSensor()
        val model = open(sensor, FakeSensorDetails(paired))
        model.connect(); runCurrent()
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertEquals(listOf("connect ${paired.id.value}"), sensor.calls)

        sensor.set(SensorState.Connected(82)); runCurrent()
        assertEquals(Mode.Connected, model.ui.value.mode)
        assertEquals(82, model.ui.value.battery)
        sensor.samples.tryEmit(HrSample(Instant.fromEpochSeconds(1), 74)); runCurrent()
        assertEquals(74, model.ui.value.bpm)
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(Mode.Connected, model.ui.value.mode, "the 30 s rule is for searching only")
        assertFalse("disconnect" in sensor.calls)
    }

    @Test
    fun no_answer_in_30_s_shows_not_found_and_stops_the_link() = runTest {
        val sensor = FakeHeartRateSensor()
        val model = open(sensor, FakeSensorDetails(paired))
        model.connect(); runCurrent()
        advanceTimeBy(29.seconds); runCurrent()
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertFalse("disconnect" in sensor.calls)
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(Mode.NotFound, model.ui.value.mode)
        assertEquals("disconnect", sensor.calls.last(), "the radio stops trying when the search gives up")

        model.connect(); runCurrent() // "try again"
        assertEquals(Mode.Searching, model.ui.value.mode)
        sensor.set(SensorState.Connected(null)); runCurrent()
        assertEquals(Mode.Connected, model.ui.value.mode)
    }

    @Test
    fun cancel_during_the_search_disconnects_and_shows_disconnected() = runTest {
        val sensor = FakeHeartRateSensor()
        val model = open(sensor, FakeSensorDetails(paired))
        model.connect(); runCurrent()
        model.cancel(); runCurrent()
        assertEquals(Mode.Disconnected, model.ui.value.mode)
        assertEquals("disconnect", sensor.calls.last())
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(Mode.Disconnected, model.ui.value.mode, "a cancelled search never turns into not found")
    }

    @Test
    fun a_link_already_searching_when_the_screen_opens_gets_the_same_30_s() = runTest {
        val sensor = FakeHeartRateSensor(SensorState.Connecting) // the launch reconnect is running
        val model = open(sensor, FakeSensorDetails(paired))
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertTrue(sensor.calls.none { it.startsWith("connect") }, "no second connect: the link is already trying")
        advanceTimeBy(30.seconds); runCurrent()
        assertEquals(Mode.NotFound, model.ui.value.mode)
    }

    @Test
    fun a_drop_while_connected_shows_searching_until_the_link_is_back() = runTest {
        val sensor = FakeHeartRateSensor(SensorState.Connected(80))
        val model = open(sensor, FakeSensorDetails(paired))
        sensor.set(SensorState.Disconnected(DisconnectReason.Lost)); runCurrent()
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertNull(model.ui.value.battery)
        sensor.set(SensorState.Connecting); sensor.set(SensorState.Connected(80)); runCurrent()
        assertEquals(Mode.Connected, model.ui.value.mode)
    }

    @Test
    fun firmware_below_2_1_0_is_flagged_only_while_connected_and_read() = runTest {
        val details = FakeSensorDetails(paired, firmware = null)
        val model = open(FakeHeartRateSensor(SensorState.Connected(90)), details)
        assertFalse(model.ui.value.firmwareTooOld, "unknown firmware is not a warning")
        details.firmware.value = "2.0.4"; runCurrent()
        assertTrue(model.ui.value.firmwareTooOld)
        assertEquals("2.0.4", model.ui.value.firmware)
        details.firmware.value = "2.2.1"; runCurrent()
        assertFalse(model.ui.value.firmwareTooOld)
    }

    @Test
    fun bluetooth_off_and_unauthorized_win_over_everything_else() = runTest {
        val sensor = FakeHeartRateSensor()
        val model = open(sensor, FakeSensorDetails(paired))
        model.connect(); runCurrent()
        sensor.set(SensorState.BluetoothOff); runCurrent()
        assertEquals(Mode.BluetoothOff, model.ui.value.mode)
        sensor.set(SensorState.Unauthorized); runCurrent()
        assertEquals(Mode.Unauthorized, model.ui.value.mode)
    }

    @Test
    fun without_a_paired_sensor_the_screen_scans_lists_polar_adverts_and_pairs_only_on_a_tap() = runTest {
        val sensor = FakeHeartRateSensor()
        val details = FakeSensorDetails(paired = null)
        val model = open(sensor, details)
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertEquals(listOf("search on"), sensor.calls)

        sensor.adverts.tryEmit(stranger); runCurrent()
        assertEquals(listOf(stranger), model.ui.value.nearby)
        advanceTimeBy(60.seconds); runCurrent()
        assertTrue(sensor.calls.none { it.startsWith("connect") }, "a stranger is never connected by itself: ${sensor.calls}")
        assertEquals(Mode.Searching, model.ui.value.mode, "something was found: keep listing, no not-found")

        model.pair(stranger.id); runCurrent()
        assertEquals(listOf("search on", "search off", "connect ${stranger.id.value}"), sensor.calls, "the scan stops when a sensor is picked")
        details.paired.value = PairedSensorInfo(stranger.id, stranger.name) // the link remembered it (adapters/01)
        sensor.set(SensorState.Connected(64)); runCurrent()
        assertEquals(Mode.Connected, model.ui.value.mode)
        assertEquals("FFEE11", model.ui.value.paired?.code)
        assertEquals(Signal.Fair, model.ui.value.signal, "from the advert that was seen (-80 dBm)")
    }

    @Test
    fun without_a_paired_sensor_and_nothing_nearby_in_30_s_shows_not_found() = runTest {
        val sensor = FakeHeartRateSensor()
        val model = open(sensor, FakeSensorDetails(paired = null))
        advanceTimeBy(30.seconds); runCurrent()
        assertEquals(Mode.NotFound, model.ui.value.mode)
        assertEquals(listOf("search on", "search off"), sensor.calls)
        model.connect(); runCurrent() // "try again" with nothing paired scans again
        assertEquals(Mode.Searching, model.ui.value.mode)
        assertEquals("search on", sensor.calls.last())
    }

    @Test
    fun the_paired_sensor_sorts_first_in_nearby_and_forget_clears_the_pairing() = runTest {
        val sensor = FakeHeartRateSensor()
        val details = FakeSensorDetails(paired = null)
        val model = open(sensor, details)
        val mine = DiscoveredSensor(paired.id, paired.name, -90)
        details.paired.value = paired
        sensor.adverts.tryEmit(stranger); sensor.adverts.tryEmit(mine); runCurrent()
        assertEquals(listOf(mine, stranger), model.ui.value.nearby, "paired first although weaker")

        model.forget(); runCurrent()
        assertEquals(listOf("forget"), details.calls)
        assertNull(model.ui.value.paired)
        assertTrue("disconnect" in sensor.calls && "search off" in sensor.calls, "${sensor.calls}")
    }

    @Test
    fun signal_buckets() {
        assertEquals(Signal.Good, Signal.of(-70))
        assertEquals(Signal.Fair, Signal.of(-71))
        assertEquals(Signal.Fair, Signal.of(-85))
        assertEquals(Signal.Weak, Signal.of(-86))
    }
}
