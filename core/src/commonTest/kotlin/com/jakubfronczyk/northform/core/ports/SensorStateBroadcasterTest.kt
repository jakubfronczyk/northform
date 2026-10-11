package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** D36 + D100, the rule every `HeartRateSensor` implementation publishes through. */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorStateBroadcasterTest {
    private val t0 = Instant.fromEpochSeconds(1_800_000_000)
    private val lost = SensorState.Disconnected(DisconnectReason.Lost)

    @Test
    fun two_changes_at_one_instant_both_reach_a_slow_collector() = runTest {
        val broadcaster = SensorStateBroadcaster(SensorStateChange(SensorState.Connected(null), t0))
        val seen = ArrayList<SensorState>()
        backgroundScope.launch { broadcaster.changes.collect { seen += it.state } }
        runCurrent()
        broadcaster.publish(SensorStateChange(lost, t0 + 1.seconds))
        broadcaster.publish(SensorStateChange(SensorState.Connecting, t0 + 1.seconds))
        runCurrent()
        assertEquals(listOf(SensorState.Connected(null), lost, SensorState.Connecting), seen)
    }

    @Test
    fun the_same_state_again_is_no_change_and_keeps_its_first_time() {
        val broadcaster = SensorStateBroadcaster(SensorStateChange(lost, t0))
        assertFalse(broadcaster.publish(SensorStateChange(lost, t0 + 5.seconds)))
        assertEquals(SensorStateChange(lost, t0), broadcaster.latest)
    }

    @Test
    fun a_stalled_collector_surfaces_instead_of_growing_a_buffer() = runTest {
        val broadcaster = SensorStateBroadcaster(SensorStateChange(SensorState.Connecting, t0))
        backgroundScope.launch { broadcaster.changes.collect { awaitCancellation() } }
        runCurrent()
        assertFailsWith<IllegalStateException> {
            repeat(SensorStateBroadcaster.bufferSize + 2) { i ->
                broadcaster.publish(SensorStateChange(if (i % 2 == 0) SensorState.BluetoothOff else SensorState.Connecting, t0 + i.seconds))
            }
        }
    }
}
