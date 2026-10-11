package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * D36: the `HeartRateSensor` port promises every state change, in order, none merged — written once here
 * and run against EVERY implementation: one subclass per implementation (Gatt in `:engine`, the replay
 * sensor in `:replay`, [ScriptedHeartRateSensor] in `:engine`). Each subclass says how its own source makes
 * the link drop, come back and lose the radio ([Driver]); the cases assert what a collector of `states()`
 * sees. A conflated `Lost → Connecting` zeroed the drop count once (adapters/02): that is the first case.
 *
 * Idiom: an abstract class (a template: the cases are written once, [connected] is the hook). The cases are `open`
 * and un-annotated because this is a main source set, where `kotlin.test.Test` does not resolve on the JVM; each
 * subclass re-declares them as `@Test override fun case() = super.case()`, as [RecordingStoreContract]'s users list theirs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class HeartRateSensorContract {
    protected val start: Instant = Instant.fromEpochSeconds(1_800_000_000)

    /** Moves one implementation through the link's life, the way its own source does. Every step stamps "now" on [virtualWallClock]. */
    interface Driver {
        val sensor: HeartRateSensor
        /** The link drops and re-linking starts at once: `Disconnected(Lost)` and `Connecting` at one instant. */
        suspend fun drop()
        /** The link is back: `Connected`. */
        suspend fun relink()
        /** The radio is switched off: `BluetoothOff`, no `Lost`. */
        suspend fun bluetoothOff()
        /** The radio is back: `Connecting`. */
        suspend fun bluetoothOn()

        companion object {
            /**
             * A driver for an implementation whose test sets each state directly (the scripted fake, the replay
             * timeline): every step is the state(s) it stands for, through [set]. Only a real link (Gatt) needs its own steps.
             */
            fun fromSetter(sensor: HeartRateSensor, set: suspend (SensorState) -> Unit): Driver = object : Driver {
                override val sensor = sensor
                override suspend fun drop() { set(SensorState.Disconnected(DisconnectReason.Lost)); set(SensorState.Connecting) }
                override suspend fun relink() = set(SensorState.Connected(battery = null))
                override suspend fun bluetoothOff() = set(SensorState.BluetoothOff)
                override suspend fun bluetoothOn() = set(SensorState.Connecting)
            }
        }
    }

    /** A sensor already `Connected`, on `virtualWallClock(start)` of this test. */
    protected abstract fun TestScope.connected(): Driver

    /** Every change a collector sees, recorded as delivered (the way the run session collects). */
    private fun TestScope.collecting(driver: Driver): List<SensorStateChange> {
        val seen = ArrayList<SensorStateChange>()
        backgroundScope.launch { driver.sensor.states().collect { seen += it } }
        runCurrent()
        assertEquals(listOf<SensorState>(SensorState.Connected(null)), seen.map { it.state.withoutBattery() }, "states() starts with the current value")
        return seen
    }

    open fun a_drop_is_delivered_as_lost_then_connecting_at_one_instant_then_connected(): TestResult = runTest {
        val driver = connected()
        val seen = collecting(driver)
        advanceTimeBy(10.seconds)
        driver.drop(); runCurrent()
        assertEquals(
            listOf(SensorState.Connected(null), SensorState.Disconnected(DisconnectReason.Lost), SensorState.Connecting),
            seen.map { it.state.withoutBattery() },
            "Lost must reach the collector even though Connecting follows at the same instant: it is the drop the log counts",
        )
        assertEquals(start + 10.seconds, seen[1].at, "the drop is stamped when it happened")
        assertEquals(seen[1].at, seen[2].at, "one instant, two changes")
        advanceTimeBy(30.seconds)
        driver.relink(); runCurrent()
        assertEquals(SensorState.Connected(null), seen.last().state.withoutBattery())
        assertEquals(30.seconds, seen.last().at - seen[1].at, "reconnect time = Connected.at − Lost.at")
    }

    open fun bluetooth_off_then_on_is_delivered_change_by_change(): TestResult = runTest {
        val driver = connected()
        val seen = collecting(driver)
        driver.bluetoothOff(); runCurrent()
        driver.bluetoothOn(); driver.relink(); runCurrent() // no collector turn in between: nothing may merge
        assertEquals(
            listOf(SensorState.BluetoothOff, SensorState.Connecting, SensorState.Connected(null)),
            seen.drop(1).map { it.state.withoutBattery() },
            "no Lost while the radio is off (D31), and no step skipped",
        )
    }

    open fun a_user_disconnect_is_delivered_and_is_not_a_drop(): TestResult = runTest {
        val driver = connected()
        val seen = collecting(driver)
        driver.sensor.disconnect(); runCurrent()
        assertEquals(listOf<SensorState>(SensorState.Disconnected(DisconnectReason.User)), seen.drop(1).map { it.state })
        assertEquals(SensorState.Disconnected(DisconnectReason.User), driver.sensor.states().first().state, "a new collector starts with the current value")
    }

    /** Battery level is the implementation's business (a fixture may carry one); the contract is about the link. */
    private fun SensorState.withoutBattery(): SensorState = if (this is SensorState.Connected) SensorState.Connected(null) else this
}
