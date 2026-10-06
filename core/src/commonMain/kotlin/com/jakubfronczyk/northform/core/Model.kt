package com.jakubfronczyk.northform.core

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The sensor as the app identifies it: the CoreBluetooth peripheral identifier (a UUID string) on
 * iOS, the MAC address on Android later (D8). Not Polar's device id — we no longer read Polar's
 * protocols in v1.0; the human-readable id is the tail of the advertised name ("Polar Sense ABC123").
 */
@JvmInline
value class SensorId(val value: String)

data class DiscoveredSensor(val id: SensorId, val name: String?, val rssi: Int)

/** One heart-rate value. `t` = arrival time (live HR carries no sample time, same as the Swift build). */
data class HrSample(val t: Instant, val bpm: Int)

/** Why a sensor is disconnected. `lost` counts as a drop in the reliability log; `user` does not. */
enum class DisconnectReason { User, Lost }

/**
 * What the sensor port reports (D92 of the Swift build, restated). A payload-carrying sealed
 * hierarchy, never an enum class (D4).
 */
sealed interface SensorState {
    data class Disconnected(val reason: DisconnectReason) : SensorState
    data object Connecting : SensorState
    data class Connected(val battery: Int?) : SensorState
    data object BluetoothOff : SensorState
    data object Unauthorized : SensorState
}

/** A state with the time it began. Same state twice in a row is one change (D100 of the Swift build). */
data class SensorStateChange(val state: SensorState, val at: Instant)

sealed class SensorError(message: String) : Exception(message) {
    data object Unauthorized : SensorError("Bluetooth permission denied")
    data object ConnectTimedOut : SensorError("sensor did not connect within the timeout")
}

/** A GPS fix as the reducer sees it. `stationary` is Core Location's own judgement (D10 keeps it). */
data class LocationFix(
    val t: Instant,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Double,
    val altitude: Double?,
    val speed: Double?,
    val stationary: Boolean?,
)
