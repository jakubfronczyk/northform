package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStore
import kotlin.time.Instant

/**
 * The store with the writer's trace lines (`RecordingSession.swift:242-282`: "created recording",
 * "persisted …", "stopped recording", "computing"). The session itself stays untouched; the decorator
 * logs what reached the store and re-throws what failed, so the session's retry path is unchanged.
 */
class LoggingStore(private val inner: RecordingStore, private val log: (String) -> Unit) : RecordingStore by inner {
    override suspend fun create(recording: Recording) {
        try { inner.create(recording); log("created recording") } catch (e: Exception) { log("create failed: $e"); throw e }
    }

    override suspend fun flush(batch: FlushBatch) {
        try {
            inner.flush(batch)
            log("persisted ${batch.hr.size} hr, ${batch.fixes.size} fixes, ${batch.events.size} events at ${batch.persistedAt}")
        } catch (e: Exception) {
            log("persist failed: $e"); throw e
        }
    }

    override suspend fun stop(id: RecordingId, endUtc: Instant) {
        try { inner.stop(id, endUtc); log("stopped recording") } catch (e: Exception) { log("stop failed: $e"); throw e }
    }

    override suspend fun beginComputing(id: RecordingId) {
        try { inner.beginComputing(id); log("computing") } catch (e: Exception) { log("begin computing failed: $e"); throw e }
    }

    override suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>) =
        inner.saveSummary(id, tag, rpe, notes, metrics)
}
