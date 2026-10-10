package com.jakubfronczyk.northform.ui.sensor

import com.jakubfronczyk.northform.core.SensorId
import kotlinx.coroutines.flow.StateFlow

/**
 * The paired sensor as the screens name it (SU3, D52: one sensor). `name` is the advertised name
 * ("Polar Sense ABC123"); its tail is the code printed on the device, which is how a person tells
 * two sensors apart (`core/Model.kt:6-10`).
 */
data class PairedSensorInfo(val id: SensorId, val name: String?) {
    /** "Polar Verity Sense" for the Sense's advertised "Polar Sense", else the advertised name, else a generic label. */
    val displayName: String
        get() = when {
            name == null -> "Polar sensor"
            name.startsWith("Polar Sense", ignoreCase = true) -> "Polar Verity Sense"
            else -> name.substringBeforeLast(' ').ifBlank { name }
        }

    /** The device code: the last word of the advertised name ("ABC123"), or the id's first 8 characters. */
    val code: String
        get() = name?.substringAfterLast(' ')?.takeIf { it.isNotBlank() && it != name } ?: id.value.take(8)
}

/**
 * What the Sensor screen reads besides the frozen `HeartRateSensor` port: the paired sensor and the
 * firmware the link read (E16). Kept out of the port on purpose (its contract is frozen, D3); the
 * composition root adapts `GattHeartRateSensor.linkState` + `PairedSensor` to it, previews use a fake.
 */
interface SensorDetails {
    val paired: StateFlow<PairedSensorInfo?>
    /** DIS 0x2A26 as read on the current link; `null` until read or when not connected. */
    val firmware: StateFlow<String?>
    /** SU3 "forget": the link disconnects and the pairing is dropped (Settings → Forget sensor). */
    suspend fun forget()
}
