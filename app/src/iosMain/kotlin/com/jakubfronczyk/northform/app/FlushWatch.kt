package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * Knows when a flush landed (persistence/02): the store, with the heartbeat of the last successful
 * flush per recording kept beside it. Kotlin interface delegation (`by inner`) forwards every other
 * port call untouched. A failed flush throws through and records nothing, so a wait outlives it
 * until the reducer's retry succeeds (D94).
 *
 * Why here and not in the session: "the batch asked for at `at` is durable" is exactly "a flush with
 * `persistedAt >= at` succeeded" — the reducer stamps that batch's heartbeat at or after `at`
 * (`RunReducer.requestPersist`) and batches are written in order — so the store's own confirmations
 * answer it without a new reducer input or handle method.
 */
class FlushWatch(private val inner: RecordingStore) : RecordingStore by inner {
    private val durable = MutableStateFlow<Map<RecordingId, Instant>>(emptyMap())

    override suspend fun flush(batch: FlushBatch) {
        inner.flush(batch)
        durable.update { it + (batch.recordingId to batch.persistedAt) }
    }

    /** Suspends until a flush of [id] whose heartbeat is at or after [at] has succeeded. */
    suspend fun awaitDurable(id: RecordingId, at: Instant) {
        durable.first { it[id]?.let { heartbeat -> heartbeat >= at } == true }
    }
}
