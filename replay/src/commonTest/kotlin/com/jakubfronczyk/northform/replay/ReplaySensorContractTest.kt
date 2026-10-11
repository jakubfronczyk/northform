package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.testing.HeartRateSensorContract
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

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
        suspend fun line(state: SensorState) = timeline.emit(ReplayInput.Sensor(state, clock.now()))
        return object : Driver {
            override val sensor = ReplaySensor(timeline)
            override suspend fun drop() { line(SensorState.Disconnected(DisconnectReason.Lost)); line(SensorState.Connecting) }
            override suspend fun relink() = line(SensorState.Connected(battery = null))
            override suspend fun bluetoothOff() = line(SensorState.BluetoothOff)
            override suspend fun bluetoothOn() = line(SensorState.Connecting)
        }
    }
}
