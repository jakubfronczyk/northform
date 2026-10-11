package com.jakubfronczyk.northform.core.ports

import kotlinx.coroutines.flow.Flow
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorStateChange

/**
 * The heart-rate port (frozen contract, D92 of the Swift build restated in Kotlin; D3: a plain
 * interface, injected). Implemented by `GattHeartRateSensor` (:engine, over any `BleCentral`), by
 * the replay sensor and by the test fakes. The Kotlin idiom: cold [Flow]s are "a new stream per call".
 *
 * Contract:
 *  - [connect] returns when connected or throws [com.jakubfronczyk.northform.core.SensorError.ConnectTimedOut] after
 *    15 s; the implementation keeps reconnecting on its own until [disconnect].
 *  - [states] starts with the current value and delivers every change, in order, none merged (D36).
 *  - [heartRate] is live samples only, stays open through drops and Bluetooth off/on, ends only on
 *    cancel, throws only [com.jakubfronczyk.northform.core.SensorError.Unauthorized].
 *  - One radio subscription is shared by all [heartRate] collectors and stopped when the last leaves.
 */
interface HeartRateSensor {
    /**
     * Starts with the current value, then delivers EVERY state change, in order, none merged (D36): two
     * changes at one instant (a drop is `Disconnected(Lost)` then `Connecting`) are two emissions, and a slow
     * collector gets both. So no conflating `StateFlow` behind it: a `SharedFlow(replay = 1)` with a buffer.
     * The same state twice in a row is one change. Every implementation runs `HeartRateSensorContract` (:testSupport).
     */
    fun states(): Flow<SensorStateChange>
    fun heartRate(): Flow<HrSample>
    fun search(): Flow<DiscoveredSensor>
    suspend fun connect(id: SensorId)
    suspend fun disconnect()
}
