package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.SensorStateBroadcaster
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Instant

/**
 * The `HeartRateSensor` fake engine and replay tests drive by hand: the test [set]s each state and emits
 * samples on [hr]. It keeps the port's promise like the real sensor (D36): every change reaches every
 * collector in order, two set at one instant included, through the same [SensorStateBroadcaster].
 * `connect`/`disconnect` answer at once ([clock] stamps them): there is no radio to wait for.
 */
class ScriptedHeartRateSensor(
    initial: SensorState = SensorState.Connected(battery = 80),
    initialAt: Instant = Instant.fromEpochSeconds(0),
    private val clock: () -> Instant = { initialAt },
) : HeartRateSensor {
    val hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
    private val states = SensorStateBroadcaster(SensorStateChange(initial, initialAt))
    val latest: SensorStateChange get() = states.latest

    /** One change, delivered as given; the same state twice in a row is one change (D100, as the real sensor). */
    fun set(state: SensorState, at: Instant = clock()) {
        states.publish(SensorStateChange(state, at))
    }

    override fun states(): Flow<SensorStateChange> = states.changes
    override fun heartRate(): Flow<HrSample> = hr
    override fun search(): Flow<DiscoveredSensor> = emptyFlow()
    override suspend fun connect(id: SensorId) = set(SensorState.Connected(battery = null))
    override suspend fun disconnect() = set(SensorState.Disconnected(DisconnectReason.User))
}
