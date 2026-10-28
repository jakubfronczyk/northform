package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.engine.run.RunSummary
import com.jakubfronczyk.northform.engine.run.saveRunSummary
import com.jakubfronczyk.northform.engine.store.StoredSavedRecordings
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * today-screen/01 — what [StoredSavedRecordings] reads over every store: run once per adapter (in memory, SQLDelight)
 * with a fresh store per case, like [RecordingStoreContract].
 */
class SavedRecordingsContract<S>(private val store: S) where S : RecordingStore, S : SavedRows {
    private val saved = StoredSavedRecordings(store, store)
    private val profile = RunHarness.config.profile

    /** A stopped recording starting at [startS]: 3 m/s north for 60 s with a 10 s pause, HR 150 when [hr]. */
    private fun data(id: String, startS: Int, type: RecordingType = RecordingType.Run, hr: Boolean = true) = RecordingData(
        recording = Recording(id = RecordingId(id), type = type, state = RecordingState.Computing, startUtc = at(startS), tzOffset = 7200, endUtc = at(startS + 60)),
        hr = if (hr) (1..60).map { HrSample(at(startS + it), 150) } else emptyList(),
        fixes = (1..60).map { fix(startS + it, north = it * 3.0) },
        events = listOf(
            RecordingEvent(at(startS + 20), EventKind.Pause, EventSource.Manual),
            RecordingEvent(at(startS + 30), EventKind.Resume, EventSource.Manual),
        ),
        lastPersistedAt = at(startS + 60),
    )

    /** Seeded through `computing` and saved the way the Summary saves, headline rows included. */
    private suspend fun save(data: RecordingData, tag: RunTag? = null): RunSummary {
        store.seedComputing(data)
        val summary = RunSummary.compute(assertNotNull(store.load(data.recording.id)), profile) // as the Summary does: the store's copy, `Stop` included
        store.saveRunSummary(summary, tag, rpe = null, notes = null)
        return summary
    }

    suspend fun newest_first_only_saved_and_the_limit_is_honoured() {
        save(data("a", 0))
        save(data("c", 2000))
        save(data("b", 1000))
        store.seedComputing(data("computing", 3000)) // never appears
        store.create(data("active", 4000).recording.copy(state = RecordingState.Active, endUtc = null))

        assertEquals(listOf("c", "b", "a"), saved.latest(5).map { it.recording.id.value })
        assertEquals(listOf("c", "b"), saved.latest(2).map { it.recording.id.value })
        assertEquals(emptyList(), saved.latest(0))
    }

    suspend fun the_stored_headline_and_the_tag_read_back_and_a_walk_has_none() {
        val run = save(data("run", 0), RunTag.Tempo)
        val walk = save(data("walk", 1000, RecordingType.Walk, hr = false))

        val (w, r) = saved.latest(2)
        assertEquals(RecordingType.Walk, w.recording.type)
        assertNull(w.recording.tag)
        assertEquals(walk.headline, w.headline)
        assertNull(w.headline.avgHr)
        assertEquals(RunTag.Tempo, r.recording.tag)
        assertEquals(run.headline, r.headline)
        assertEquals(Headline(run.distance, 60.seconds, 50.seconds, 150), r.headline)
    }

    /** history-screen/03 (D24): the profile the metrics were stored with, so a reopened Summary is computed as it was. */
    suspend fun each_saved_recording_carries_the_profile_its_metrics_were_stored_with() {
        val stored = profile.copy(hrMax = 201)
        val data = data("run", 0)
        store.seedComputing(data)
        store.saveRunSummary(RunSummary.compute(assertNotNull(store.load(data.recording.id)), stored), tag = null, rpe = null, notes = null)
        val bare = data("bare", 1000)
        store.seedComputing(bare)
        store.saveSummary(bare.recording.id, tag = null, rpe = null, notes = null, metrics = emptyList())

        val (b, r) = saved.latest(2)
        assertEquals(stored, r.profile)
        assertNull(b.profile, "no metric rows, no stored profile")
    }

    /** Saved before Save stored the headline: computed from the streams on read, the same numbers. */
    suspend fun a_recording_without_the_headline_rows_is_computed_from_its_streams() {
        val data = data("old", 0)
        store.seedComputing(data)
        val summary = RunSummary.compute(assertNotNull(store.load(data.recording.id)), profile)
        store.saveSummary(data.recording.id, RunTag.Easy, rpe = null, notes = null, metrics = summary.metrics(null).filter { it.metricId !in headlineIds })

        assertEquals(summary.headline, saved.latest(1).single().headline)
    }

    private val headlineIds = with(RecordingMetrics.Ids) { setOf(distance, totalTime, movingTime, avgHr) }
}
