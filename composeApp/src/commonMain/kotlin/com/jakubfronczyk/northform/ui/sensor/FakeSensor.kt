package com.jakubfronczyk.northform.ui.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlin.time.Instant

/**
 * A `HeartRateSensor` the previews and the screen-model tests drive by hand: the test sets the state,
 * emits samples and adverts, and reads back every call. `connect` never returns by itself (the real
 * link keeps trying until told to stop); a test answers it by setting [state] to `Connected`.
 */
class FakeHeartRateSensor(initial: SensorState = SensorState.Disconnected(DisconnectReason.User)) : HeartRateSensor {
    val state = MutableStateFlow(SensorStateChange(initial, Instant.fromEpochSeconds(0)))
    val samples = MutableSharedFlow<HrSample>(extraBufferCapacity = 16)
    val adverts = MutableSharedFlow<DiscoveredSensor>(extraBufferCapacity = 16)
    val calls = ArrayList<String>()

    fun set(state: SensorState, at: Instant = Instant.fromEpochSeconds(0)) { this.state.value = SensorStateChange(state, at) }

    override fun states(): Flow<SensorStateChange> = state
    override fun heartRate(): Flow<HrSample> = samples
    override fun search(): Flow<DiscoveredSensor> = adverts.onStart { calls += "search on" }.onCompletion { calls += "search off" }
    override suspend fun connect(id: SensorId) { calls += "connect ${id.value}"; awaitCancellation() }
    override suspend fun disconnect() { calls += "disconnect"; set(SensorState.Disconnected(DisconnectReason.User)) }
}

/** Paired sensor + firmware from plain values; `forget` clears the pairing. */
class FakeSensorDetails(paired: PairedSensorInfo? = sample, firmware: String? = null) : SensorDetails {
    override val paired = MutableStateFlow(paired)
    override val firmware = MutableStateFlow(firmware)
    val calls = ArrayList<String>()
    override suspend fun forget() { calls += "forget"; paired.value = null }

    companion object {
        val sample = PairedSensorInfo(SensorId("3F2504E0-4F89-11D3-9A0C-0305E82C3301"), "Polar Sense A1B2C3D4")
    }
}
