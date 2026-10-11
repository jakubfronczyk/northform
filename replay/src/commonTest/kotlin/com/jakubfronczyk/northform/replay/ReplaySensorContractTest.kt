package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.testing.HeartRateSensorContract
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * D36 on the replay sensor: a fixture's `sensor` lines reach the collector one by one, two at one instant
 * included (a recorded drop is `lost` and `connecting` at the same millisecond), exactly as the timeline plays them.
 */
class ReplaySensorContractTest : HeartRateSensorContract() {
    // D36: the shared cases, run on this implementation.
    @Test override fun a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected() = super.a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected()
    @Test override fun bluetooth_off_then_on_is_delivered_change_by_change() = super.bluetooth_off_then_on_is_delivered_change_by_change()
    @Test override fun a_user_disconnect_is_delivered_and_is_not_a_drop() = super.a_user_disconnect_is_delivered_and_is_not_a_drop()

    override fun TestScope.connected(): Driver {
        val clock = virtualWallClock(start)
        val timeline = ReplayTimeline(ReplayScript(ReplayMeta(RecordingType.Run, start, 187, 60, Sex.Male), emptyList()), clock)
        return Driver.fromSetter(ReplaySensor(timeline)) { timeline.emit(ReplayInput.Sensor(it, clock.now())) }
    }

    /** D100 on the replay sensor: a fixture line repeating the current state (later) is no change, as on Gatt and the fake. */
    @Test
    fun the_same_state_again_later_is_one_change() = runTest {
        val clock = virtualWallClock(start)
        val timeline = ReplayTimeline(ReplayScript(ReplayMeta(RecordingType.Run, start, 187, 60, Sex.Male), emptyList()), clock)
        val seen = ArrayList<SensorStateChange>()
        backgroundScope.launch { timeline.sensorStates.collect { seen += it } }
        runCurrent()
        val lost = SensorState.Disconnected(DisconnectReason.Lost)
        timeline.emit(ReplayInput.Sensor(lost, start + 1.seconds))
        timeline.emit(ReplayInput.Sensor(lost, start + 5.seconds))
        runCurrent()
        assertEquals(listOf(SensorStateChange(lost, start + 1.seconds)), seen.drop(1), "one loss, at its first time")
    }
}
