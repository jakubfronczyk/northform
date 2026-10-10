package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import com.jakubfronczyk.northform.ui.sensor.SensorDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Sensor screen's `SensorDetails` over the real link: the pairing the link decided (adapters/01,
 * persisted in [PairedSensor]) and the firmware it read. Built in `boot()`; [remember] is the link's
 * `onPaired` callback.
 */
class LinkSensorDetails(private val sensor: GattHeartRateSensor, scope: CoroutineScope) : SensorDetails {
    override val paired = MutableStateFlow(PairedSensor.id?.let { PairedSensorInfo(SensorId(it), PairedSensor.name) })

    /** `stateIn(Eagerly)`: a hot copy of the link's firmware field, `null` until read (the idiom for exposing one field of a bigger state). */
    override val firmware: StateFlow<String?> = sensor.linkState.map { it.firmware }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The link's `Remember(id, name)` / `Forget` (null): persist and publish. A later `Remember` without a name keeps the one stored. */
    fun remember(id: SensorId?, name: String?) {
        val kept = name ?: PairedSensor.name.takeIf { PairedSensor.id == id?.value }
        PairedSensor.id = id?.value
        PairedSensor.name = kept.takeIf { id != null }
        paired.value = id?.let { PairedSensorInfo(it, kept) }
    }

    override suspend fun forget() = sensor.forget()
}
