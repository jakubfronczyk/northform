package com.jakubfronczyk.northform.ui.sensor

import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.testing.HeartRateSensorContract
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test

/** D36 on the screen's hand-driven fake: a conflating fake would let a screen-model test pass on a merged Lost → Connecting. */
class FakeHeartRateSensorContractTest : HeartRateSensorContract() {
    @Test override fun a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected() = super.a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected()
    @Test override fun bluetooth_off_then_on_is_delivered_change_by_change() = super.bluetooth_off_then_on_is_delivered_change_by_change()
    @Test override fun a_user_disconnect_is_delivered_and_is_not_a_drop() = super.a_user_disconnect_is_delivered_and_is_not_a_drop()

    override fun TestScope.connected(): Driver {
        val clock = virtualWallClock(start)
        val fake = FakeHeartRateSensor(SensorState.Connected(battery = 80))
        return Driver.fromSetter(fake) { fake.set(it, clock.now()) }
    }
}
