package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Instant

/**
 * The `HeartRateSensor` fake engine and replay tests drive by hand: the test [set]s each state and emits
 * samples on [hr]. It keeps the port's promise like the real sensor (D36): every change reaches every
 * collector in order, two set at one instant included — a `MutableSharedFlow` with `replay = 1` (the
 * port's "starts with the current value") and an unbounded buffer, never a conflating `StateFlow`.
 * `connect`/`disconnect` answer at once ([clock] stamps them): there is no radio to wait for.
 */
class ScriptedHeartRateSensor(
    initial: SensorState = SensorState.Connected(battery = 80),
    initialAt: Instant = Instant.fromEpochSeconds(0),
    private val clock: () -> Instant = { initialAt },
) : HeartRateSensor {
    val hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
    private val states = MutableSharedFlow<SensorStateChange>(replay = 1, extraBufferCapacity = Int.MAX_VALUE)
    var latest: SensorStateChange = SensorStateChange(initial, initialAt).also { states.tryEmit(it) }
        private set

    /** One change, delivered as given; the same state twice in a row is one change (D100, as the real sensor). */
    fun set(state: SensorState, at: Instant = clock()) {
        if (latest.state == state) return
        latest = SensorStateChange(state, at)
        check(states.tryEmit(latest)) { "unbounded state buffer refused an emit" }
    }

    override fun states(): Flow<SensorStateChange> = states
    override fun heartRate(): Flow<HrSample> = hr
    override fun search(): Flow<DiscoveredSensor> = emptyFlow()
    override suspend fun connect(id: SensorId) = set(SensorState.Connected(battery = null))
    override suspend fun disconnect() = set(SensorState.Disconnected(DisconnectReason.User))
}
