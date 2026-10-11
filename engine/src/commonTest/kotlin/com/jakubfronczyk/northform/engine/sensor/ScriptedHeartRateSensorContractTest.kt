package com.jakubfronczyk.northform.engine.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.testing.HeartRateSensorContract
import com.jakubfronczyk.northform.testing.ScriptedHeartRateSensor
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

/** D36 on the hand-driven fake the engine and replay tests use: it must keep the port's promise too, or a test on it proves nothing. */
class ScriptedHeartRateSensorContractTest : HeartRateSensorContract() {
    // D36: the shared cases, run on this implementation.
    @Test override fun a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected() = super.a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected()
    @Test override fun bluetooth_off_then_on_is_delivered_change_by_change() = super.bluetooth_off_then_on_is_delivered_change_by_change()
    @Test override fun a_user_disconnect_is_delivered_and_is_not_a_drop() = super.a_user_disconnect_is_delivered_and_is_not_a_drop()

    override fun TestScope.connected(): Driver {
        val clock = virtualWallClock(start)
        val fake = ScriptedHeartRateSensor(SensorState.Connected(battery = 80), clock.now(), clock::now)
        return object : Driver {
            override val sensor = fake
            override suspend fun drop() { fake.set(SensorState.Disconnected(DisconnectReason.Lost)); fake.set(SensorState.Connecting) }
            override suspend fun relink() = fake.set(SensorState.Connected(battery = 80))
            override suspend fun bluetoothOff() = fake.set(SensorState.BluetoothOff)
            override suspend fun bluetoothOn() = fake.set(SensorState.Connecting)
        }
    }
}
