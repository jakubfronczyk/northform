package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RunTag
import kotlin.time.Instant

// Ported from northform-ios `Core/Ports/RecordingStore.swift` (frozen contract, D92/D109 there).

/** `RecordingStore.swift:4` — everything written in one flush (at most every 5 s), as one transaction. */
data class FlushBatch(
    val recordingId: RecordingId,
    val hr: List<HrSample> = emptyList(),
    val fixes: List<LocationFix> = emptyList(),
    val events: List<RecordingEvent> = emptyList(),
    /** Heartbeat: after a crash, everything after this counts as lost HR and GPS (R28). */
    val persistedAt: Instant,
)

/** `RecordingStore.swift:28` — a recording with all its raw data, for the summary and crash resume. */
data class RecordingData(
    val recording: Recording,
    val hr: List<HrSample>,
    val fixes: List<LocationFix>,
    val events: List<RecordingEvent>,
    val lastPersistedAt: Instant?,
)

/** `RecordingStore.swift:50`. */
sealed class RecordingStoreError(message: String) : Exception(message) {
    data class NotFound(val id: RecordingId) : RecordingStoreError("not found: ${id.value}")
    data class AlreadyExists(val id: RecordingId) : RecordingStoreError("already exists: ${id.value}")
    /** The step isn't legal from the recording's current state. */
    data class IllegalTransition(val from: RecordingState, val to: RecordingState) : RecordingStoreError("illegal transition $from → $to")
}

/**
 * `RecordingStore.swift:60` — durable storage. Each method is one transaction and one legal step of
 * the live state machine: active → stopping → computing → saved. Flush is allowed in active and
 * stopping. Swift `async throws` → Kotlin `suspend` that throws [RecordingStoreError].
 */
interface RecordingStore {
    /** Writes the first row, in state `active`, when the countdown ends. No row exists during gate and countdown. */
    suspend fun create(recording: Recording)
    suspend fun flush(batch: FlushBatch)
    /** Hold-to-stop: writes `endUtc` and the `Stop` event together, state → `stopping`. The reducer never emits `Stop` itself. */
    suspend fun stop(id: RecordingId, endUtc: Instant)
    /** After the cool-down: `stopping` → `computing`. */
    suspend fun beginComputing(id: RecordingId)
    /** Summary → Save; state → `saved`. */
    suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>)
    suspend fun delete(id: RecordingId)
    /** The recording a crash left in `active`, `stopping` or `computing`, if any (R28). */
    suspend fun unfinished(): Recording?
    suspend fun load(id: RecordingId): RecordingData?
}
