package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStoreError
import kotlin.time.Instant

/**
 * `Replay/MemoryRecords.swift` — recordings kept in memory with the same rules as the real store.
 * Synchronous and plain (no coroutines), so the reducer harness and the tests can drive it directly;
 * `:replay`'s `InMemoryRecordingStore` wraps it behind the suspend port. Lives in `:engine` so the
 * engine's own tests can use it without depending on `:replay`.
 *
 * Swift's struct had value semantics (a copy per checkpoint); here [copy] does that explicitly.
 */
class MemoryRecords private constructor(
    private val entries: LinkedHashMap<RecordingId, Entry>,
) {
    constructor() : this(LinkedHashMap())

    private data class Entry(
        val recording: Recording,
        val hr: List<HrSample> = emptyList(),
        val fixes: List<LocationFix> = emptyList(),
        val events: List<RecordingEvent> = emptyList(),
        val metrics: List<MetricResult> = emptyList(),
        val lastPersistedAt: Instant? = null,
    )

    /** A snapshot of the store as it is now (the Swift struct copy). Entries are immutable, so a shallow map copy is enough. */
    fun copy(): MemoryRecords = MemoryRecords(LinkedHashMap(entries))

    fun create(recording: Recording) {
        if (entries.containsKey(recording.id)) throw RecordingStoreError.AlreadyExists(recording.id)
        if (recording.state != RecordingState.Active) throw RecordingStoreError.IllegalTransition(recording.state, RecordingState.Active)
        entries[recording.id] = Entry(recording)
    }

    fun flush(batch: FlushBatch) {
        val entry = entry(batch.recordingId)
        if (entry.recording.state != RecordingState.Active && entry.recording.state != RecordingState.Stopping) {
            throw RecordingStoreError.IllegalTransition(entry.recording.state, entry.recording.state)
        }
        entries[batch.recordingId] = entry.copy(
            hr = entry.hr + batch.hr,
            fixes = entry.fixes + batch.fixes,
            events = entry.events + batch.events,
            lastPersistedAt = batch.persistedAt,
        )
    }

    fun stop(id: RecordingId, endUtc: Instant) {
        val entry = entry(id)
        check(entry, from = RecordingState.Active, to = RecordingState.Stopping)
        entries[id] = entry.copy(
            recording = entry.recording.copy(state = RecordingState.Stopping, endUtc = endUtc),
            events = entry.events + RecordingEvent(endUtc, EventKind.Stop, EventSource.Manual),
        )
    }

    fun beginComputing(id: RecordingId) {
        val entry = entry(id)
        check(entry, from = RecordingState.Stopping, to = RecordingState.Computing)
        entries[id] = entry.copy(recording = entry.recording.copy(state = RecordingState.Computing))
    }

    fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>) {
        val entry = entry(id)
        check(entry, from = RecordingState.Computing, to = RecordingState.Saved)
        entries[id] = entry.copy(
            recording = entry.recording.copy(state = RecordingState.Saved, tag = tag, rpe = rpe, notes = notes),
            metrics = metrics,
        )
    }

    fun delete(id: RecordingId) {
        entries.remove(id) ?: throw RecordingStoreError.NotFound(id)
    }

    fun unfinished(): Recording? = entries.values.map { it.recording }.firstOrNull { it.state != RecordingState.Saved }

    fun load(id: RecordingId): RecordingData? = entries[id]?.let {
        RecordingData(it.recording, it.hr, it.fixes, it.events, it.lastPersistedAt)
    }

    private fun entry(id: RecordingId): Entry = entries[id] ?: throw RecordingStoreError.NotFound(id)

    private fun check(entry: Entry, from: RecordingState, to: RecordingState) {
        if (entry.recording.state != from) throw RecordingStoreError.IllegalTransition(entry.recording.state, to)
    }
}
