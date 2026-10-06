package com.jakubfronczyk.northform.core.ports

import kotlinx.coroutines.flow.Flow
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorStateChange

/**
 * The heart-rate port (frozen contract, D92 of the Swift build restated in Kotlin; D3: a plain
 * interface, injected). Implemented by `GattHeartRateSensor` (:engine, over any `BleCentral`) and by
 * the replay sensor. The Kotlin idiom: cold [Flow]s are "a new stream per call".
 *
 * Contract:
 *  - [connect] returns when connected or throws [com.jakubfronczyk.northform.core.SensorError.ConnectTimedOut] after
 *    15 s; the implementation keeps reconnecting on its own until [disconnect].
 *  - [states] starts with the current value. [heartRate] is live samples only, stays open through
 *    drops and Bluetooth off/on, ends only on cancel, throws only
 *    [com.jakubfronczyk.northform.core.SensorError.Unauthorized].
 *  - One radio subscription is shared by all [heartRate] collectors and stopped when the last leaves.
 */
interface HeartRateSensor {
    fun states(): Flow<SensorStateChange>
    fun heartRate(): Flow<HrSample>
    fun search(): Flow<DiscoveredSensor>
    suspend fun connect(id: SensorId)
    suspend fun disconnect()
}
