package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The one equal-time rule `rebuild` and the harness share (D94). */
class InputOrderTest {
    private val hr = RunInput.Hr(HrSample(at(10), 120))
    private val fixAt10 = RunInput.Fix(fix(10, north = 0.0))
    private val pause = RunInput.User(UserAction.Pause, at(10))
    private val sensor = RunInput.Sensor(SensorState.Connected(battery = null), at(10))
    private val tick = RunInput.Tick(at(10))

    @Test
    fun at_one_instant_samples_go_before_events_before_ticks() {
        assertEquals(listOf(hr, fixAt10, pause, sensor, tick), InputOrder.sorted(listOf(tick, pause, hr, sensor, fixAt10)))
    }

    @Test
    fun otherwise_time_first_then_the_order_given() {
        val earlier = RunInput.Tick(at(9))
        val fixAgain = RunInput.Fix(fix(10, north = 1.0))
        assertEquals(listOf(earlier, fixAt10, fixAgain, hr), InputOrder.sorted(listOf(fixAt10, fixAgain, hr, earlier)), "stable: equal keys keep their order")
    }

    @Test
    fun a_store_confirmation_cannot_be_ordered() {
        assertFailsWith<IllegalStateException> { InputOrder.sorted(listOf(tick, RunInput.Persisted(1))) }
    }
}
