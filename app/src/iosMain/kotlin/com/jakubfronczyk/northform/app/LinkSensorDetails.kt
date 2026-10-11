package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.engine.sensor.SensorLink
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import com.jakubfronczyk.northform.ui.sensor.SensorDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Sensor screen's `SensorDetails` over the real link: the pairing the link decided (adapters/01)
 * and the firmware it read — both one field of `linkState`. Built in `boot()`, which also persists
 * the pairing to [PairedSensor] here, from the one collector on [paired].
 */
class LinkSensorDetails(private val sensor: GattHeartRateSensor, scope: CoroutineScope) : SensorDetails {
    /** `stateIn(Eagerly)`: a hot copy of the link's pairing (the idiom for exposing one field of a bigger state). */
    override val paired: StateFlow<PairedSensorInfo?> =
        sensor.linkState.map { it.pairedInfo() }.stateIn(scope, SharingStarted.Eagerly, sensor.linkState.value.pairedInfo())

    /** Same idiom: the firmware, `null` until read. */
    override val firmware: StateFlow<String?> = sensor.linkState.map { it.firmware }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The one place the pairing is written down: every change, as the link decided it (SU3).
        scope.launch {
            paired.collect {
                PairedSensor.id = it?.id?.value
                PairedSensor.name = it?.name
            }
        }
    }

    override suspend fun forget() = sensor.forget()

    companion object {
        /** Where the link starts at boot: the pairing stored last time, its name among the known ones. */
        fun storedLinkState(): SensorLink.State {
            val id = PairedSensor.id?.let(::SensorId) ?: return SensorLink.State()
            return SensorLink.State(paired = id, knownNames = PairedSensor.name?.let { mapOf(id to it) } ?: emptyMap())
        }

        private fun SensorLink.State.pairedInfo() = paired?.let { PairedSensorInfo(it, knownNames[it]) }
    }
}
