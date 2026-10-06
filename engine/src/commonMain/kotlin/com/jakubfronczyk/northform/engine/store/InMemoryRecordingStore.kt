package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * `RecordingStore` in memory (`Replay/MemoryRecords.swift:95`), for the simulator and the walking
 * skeleton until the database exists (D7/D12 are Phase 1). Lost on quit. The Swift `actor` becomes
 * a `Mutex` around the plain records — no reentrancy here, each method is one locked step.
 */
class InMemoryRecordingStore : RecordingStore {
    val records = MemoryRecords()
    private val lock = Mutex()

    override suspend fun create(recording: Recording) = lock.withLock { records.create(recording) }
    override suspend fun flush(batch: FlushBatch) = lock.withLock { records.flush(batch) }
    override suspend fun stop(id: RecordingId, endUtc: Instant) = lock.withLock { records.stop(id, endUtc) }
    override suspend fun beginComputing(id: RecordingId) = lock.withLock { records.beginComputing(id) }
    override suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>) =
        lock.withLock { records.saveSummary(id, tag, rpe, notes, metrics) }
    override suspend fun delete(id: RecordingId) = lock.withLock { records.delete(id) }
    override suspend fun unfinished(): Recording? = lock.withLock { records.unfinished() }
    override suspend fun load(id: RecordingId): RecordingData? = lock.withLock { records.load(id) }
}
