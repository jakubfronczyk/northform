package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.ReliabilityStore
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.seedComputing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** summary-screen/01: the Summary's lifecycle over the store, from `computing` to saved or deleted. */
class SummarySessionTest {
    private val config = RunHarness.config

    /** A run from 5 s to 60 s, 3 m/s north, HR 150, one sensor drop at 20–25 s, cool-down ended at 120 s. */
    private fun stored() = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).filter { it !in 20..25 }.map { HrSample(at(it), 150) } + (61..120).map { HrSample(at(it), 120) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(
            RecordingEvent(at(20), EventKind.SensorLost, EventSource.Auto),
            RecordingEvent(at(25), EventKind.HrLost, EventSource.Auto),
            RecordingEvent(at(26), EventKind.SensorReconnected, EventSource.Auto),
            RecordingEvent(at(26), EventKind.HrBack, EventSource.Auto),
            RecordingEvent(at(60), EventKind.Stop, EventSource.Manual),
            RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto),
        ),
        lastPersistedAt = at(120),
    )

    @Test
    fun a_computing_recording_loads_into_ready_with_its_reliability_row_written() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val session = SummarySession(config.recordingId, store, store, config.profile)
        assertIs<SummarySession.State.Computing>(session.state.value)

        session.load()

        val ready = assertIs<SummarySession.State.Ready>(session.state.value)
        assertEquals(55.seconds, ready.summary.totalTime)
        assertEquals(162.0, ready.summary.distance, 0.01, "54 fixes × 3 m from the first fix's anchor")
        assertEquals(150, ready.summary.hrAvg)
        val reliability = assertNotNull(store.records.reliability(config.recordingId), "written before the screen shows")
        assertEquals(1, reliability.drops)
    }
}

class SummarySessionSaveTest {
    private val config = RunHarness.config

    private fun stored() = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).map { HrSample(at(it), 150) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(RecordingEvent(at(60), EventKind.Stop, EventSource.Manual), RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(120),
    )

    @Test
    fun save_stores_the_edits_with_the_metrics_and_moves_the_recording_to_saved() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()

        session.edit(SummaryEdits(tag = RunTag.Tempo, rpe = 7, notes = "windy")); session.save()

        assertIs<SummarySession.State.Saved>(session.state.value)
        val saved = assertNotNull(store.load(config.recordingId)).recording
        assertEquals(RecordingState.Saved, saved.state)
        assertEquals(RunTag.Tempo, saved.tag)
        assertEquals(7, saved.rpe)
        assertEquals("windy", saved.notes)
        assertTrue(store.records.metrics(config.recordingId).any { it.metricId == RecordingMetrics.Ids.sRpe }, "the sRPE row needs the RPE")
    }

    @Test
    fun a_store_that_throws_on_save_leaves_the_session_ready_with_the_error_and_nothing_saved() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val failing = object : RecordingStore by store {
            override suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>) =
                throw RuntimeException("disk full")
        }
        val session = SummarySession(config.recordingId, failing, store, config.profile)
        session.load()

        session.edit(SummaryEdits(tag = null, rpe = 5, notes = null)); session.save()

        val ready = assertIs<SummarySession.State.Ready>(session.state.value)
        assertEquals("disk full", ready.error)
        assertEquals(RecordingState.Computing, assertNotNull(store.load(config.recordingId)).recording.state)
    }
}

/** summary-screen/02: Delete, the walk's null tag, and one write per Save however many taps. */
class SummarySessionEditsTest {
    private val config = RunHarness.config

    private fun stored(type: RecordingType = RecordingType.Run) = RecordingData(
        recording = Recording(id = config.recordingId, type = type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).map { HrSample(at(it), 150) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(RecordingEvent(at(60), EventKind.Stop, EventSource.Manual), RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(120),
    )

    @Test
    fun delete_removes_the_recording_and_ends_the_session() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()

        session.delete()

        assertIs<SummarySession.State.Deleted>(session.state.value)
        assertNull(store.load(config.recordingId))
    }

    @Test
    fun a_walk_saves_no_tag_whatever_the_edits_say() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored(RecordingType.Walk))
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()

        session.edit(SummaryEdits(tag = RunTag.Easy, rpe = 3, notes = null)); session.save()

        assertNull(assertNotNull(store.load(config.recordingId)).recording.tag)
    }

    @Test
    fun a_second_save_while_the_first_is_in_flight_writes_nothing() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val slow = object : RecordingStore by store {
            override suspend fun saveSummary(id: RecordingId, tag: RunTag?, rpe: Int?, notes: String?, metrics: List<MetricResult>) {
                writes++
                gate.await()
                store.saveSummary(id, tag, rpe, notes, metrics)
            }
        }
        val session = SummarySession(config.recordingId, slow, store, config.profile)
        session.load()

        session.edit(SummaryEdits(tag = null, rpe = 6, notes = "first"))
        val first = launch { session.save() }
        runCurrent()
        assertTrue(assertIs<SummarySession.State.Ready>(session.state.value).busy, "Save and Delete are disabled while the store answers")
        session.save() // the double tap
        session.delete() // and a Delete during the write
        gate.complete(Unit)
        first.join()

        assertEquals(1, writes)
        assertIs<SummarySession.State.Saved>(session.state.value)
        assertEquals("first", assertNotNull(store.load(config.recordingId)).recording.notes)
    }
}

/** summary-screen/04: accepting a higher HRmax recomputes this Summary; Save stores metrics carrying it; the app profile is untouched. */
class SummarySessionHrMaxTest {
    private val config = RunHarness.config

    /** HR 150, then 30 s at 195 (above the profile's 187) before the stop. */
    private fun stored() = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).map { HrSample(at(it), if (it >= 30) 195 else 150) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(RecordingEvent(at(60), EventKind.Stop, EventSource.Manual), RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(120),
    )

    @Test
    fun accepting_the_suggestion_recomputes_with_the_new_hrmax_and_save_stores_it_with_the_metrics() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()
        val before = assertIs<SummarySession.State.Ready>(session.state.value).summary
        assertEquals(195, before.hrMaxSuggestion)
        assertEquals(187, before.profile.hrMax)

        session.acceptHrMax()

        val after = assertIs<SummarySession.State.Ready>(session.state.value).summary
        assertEquals(195, after.profile.hrMax)
        assertNull(after.hrMaxSuggestion, "the card goes away once accepted")
        assertTrue(after.timeInZone != before.timeInZone, "the zones moved with the new reserve")

        session.edit(SummaryEdits(tag = null, rpe = null, notes = null)); session.save()
        assertTrue(store.records.metrics(config.recordingId).all { it.profile.hrMax == 195 }, "D24: the metrics carry the profile they were computed with")
        assertEquals(187, config.profile.hrMax, "the app's profile is not touched (Settings owns that)")
    }

    @Test
    fun accept_without_a_suggestion_changes_nothing() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored().let { d -> d.copy(hr = d.hr.map { it.copy(bpm = 150) }) })
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()
        val before = session.state.value
        session.acceptHrMax()
        assertEquals(before, session.state.value)
    }
}

/** summary-screen/05: a recording that cannot be loaded or computed shows Failed, with Retry and Delete as the way out. */
class SummarySessionFailedTest {
    private val config = RunHarness.config

    private fun stored() = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).map { HrSample(at(it), 150) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(RecordingEvent(at(60), EventKind.Stop, EventSource.Manual), RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(120),
    )

    @Test
    fun a_missing_recording_is_failed_not_a_crash() = runTest {
        val store = InMemoryRecordingStore()
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()
        val failed = assertIs<SummarySession.State.Failed>(session.state.value)
        assertTrue("not found" in failed.reason, failed.reason)
    }

    @Test
    fun a_store_that_throws_on_load_is_failed_and_retry_recovers_once_it_answers() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        var broken = true
        val flaky = object : RecordingStore by store {
            override suspend fun load(id: RecordingId): RecordingData? = if (broken) throw RuntimeException("database locked") else store.load(id)
        }
        val session = SummarySession(config.recordingId, flaky, store, config.profile)
        session.load()
        assertEquals("database locked", assertIs<SummarySession.State.Failed>(session.state.value).reason)

        broken = false
        session.retry()

        assertIs<SummarySession.State.Ready>(session.state.value)
    }

    @Test
    fun corrupt_data_that_cannot_be_computed_is_failed_and_delete_still_works() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val unstopped = object : RecordingStore by store {
            override suspend fun load(id: RecordingId): RecordingData? = store.load(id)?.let { it.copy(recording = it.recording.copy(endUtc = null)) } // no stop: compute refuses
        }
        val session = SummarySession(config.recordingId, unstopped, store, config.profile)
        session.load()
        assertIs<SummarySession.State.Failed>(session.state.value)

        session.delete()

        assertIs<SummarySession.State.Deleted>(session.state.value)
        assertNull(store.load(config.recordingId))
    }
}

/** code review c.1: the reliability row is written before compute, so a run that never computes still counts its drops. */
class SummarySessionReliabilityTest {
    private val config = RunHarness.config

    private fun stored() = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = (6..60).map { HrSample(at(it), 150) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(RecordingEvent(at(20), EventKind.SensorLost, EventSource.Auto), RecordingEvent(at(26), EventKind.SensorReconnected, EventSource.Auto), RecordingEvent(at(60), EventKind.Stop, EventSource.Manual), RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(120),
    )

    @Test
    fun a_run_that_cannot_be_computed_still_gets_its_reliability_row() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val unstopped = object : RecordingStore by store {
            override suspend fun load(id: RecordingId): RecordingData? = store.load(id)?.let { it.copy(recording = it.recording.copy(endUtc = null)) }
        }
        val session = SummarySession(config.recordingId, unstopped, store, config.profile)
        session.load()
        assertIs<SummarySession.State.Failed>(session.state.value)
        assertEquals(1, assertNotNull(store.records.reliability(config.recordingId)).drops, "written before compute, so Delete from Failed keeps the drop counted")
    }

    @Test
    fun a_reliability_store_that_throws_does_not_fail_a_computable_run() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored())
        val broken = object : ReliabilityStore { override suspend fun saveReliability(id: RecordingId, record: ReliabilityRecord) = throw RuntimeException("disk full") }
        val session = SummarySession(config.recordingId, store, broken, config.profile)
        session.load()
        assertIs<SummarySession.State.Ready>(session.state.value, "the row is a cache of the data; losing it costs a recompute, not the Summary")
    }
}

/** code review a.1: the session owns the edits, preselecting the suggested tag, so the screen takes them as parameters. */
class SummarySessionEditsFlowTest {
    private val config = RunHarness.config

    @Test
    fun the_edits_start_with_the_suggested_tag_and_save_stores_what_was_edited() = runTest {
        val store = InMemoryRecordingStore()
        val tempo = RecordingData(
            recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(0), tzOffset = config.tzOffset, endUtc = at(1500)),
            hr = (1..1500).map { HrSample(at(it), 168) }, // 85% HRR for 25 min: a Tempo by R0
            fixes = (1..1500).map { fix(it, north = it * 4.0) },
            events = listOf(RecordingEvent(at(1500), EventKind.Stop, EventSource.Manual), RecordingEvent(at(1560), EventKind.CooldownEnd, EventSource.Auto)),
            lastPersistedAt = at(1560),
        )
        store.seedComputing(tempo)
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()
        val suggested = assertIs<SummarySession.State.Ready>(session.state.value).summary.suggestedTag
        assertNotNull(suggested, "the fixture must suggest a tag")
        assertEquals(SummaryEdits(tag = suggested, rpe = null, notes = null), session.edits.value)

        session.edit(session.edits.value.copy(rpe = 8))
        session.save()

        val saved = assertNotNull(store.load(config.recordingId)).recording
        assertEquals(suggested, saved.tag)
        assertEquals(8, saved.rpe)
    }
}

/** today-screen/01: Save stores the headline numbers Today and History read back. */
class SummarySessionHeadlineTest {
    private val config = RunHarness.config

    /** 5 s → 60 s, paused 30–40 s, 3 m/s north while moving; HR 150 unless [hr] is false. */
    private fun stored(type: RecordingType, hr: Boolean) = RecordingData(
        recording = Recording(id = config.recordingId, type = type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
        hr = if (hr) (6..60).map { HrSample(at(it), 150) } else emptyList(),
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = listOf(
            RecordingEvent(at(30), EventKind.Pause, EventSource.Manual),
            RecordingEvent(at(40), EventKind.Resume, EventSource.Manual),
            RecordingEvent(at(60), EventKind.Stop, EventSource.Manual),
            RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto),
        ),
        lastPersistedAt = at(120),
    )

    private suspend fun saved(type: RecordingType, hr: Boolean): Pair<RunSummary, Map<String, Double>> {
        val store = InMemoryRecordingStore()
        store.seedComputing(stored(type, hr))
        val session = SummarySession(config.recordingId, store, store, config.profile)
        session.load()
        val summary = assertIs<SummarySession.State.Ready>(session.state.value).summary
        session.save()
        return summary to store.records.metrics(config.recordingId).associate { it.metricId.value to it.value }
    }

    @Test
    fun save_stores_distance_total_moving_time_and_average_hr() = runTest {
        val (summary, metrics) = saved(RecordingType.Run, hr = true)

        assertEquals(summary.distance, metrics.getValue("distance"))
        assertEquals(55.0, metrics.getValue("total_time"))
        assertEquals(45.0, metrics.getValue("moving_time"), "the pause is out of moving time")
        assertEquals(150.0, metrics.getValue("avg_hr"))
    }

    @Test
    fun a_walk_without_heart_rate_stores_no_average_hr() = runTest {
        val (_, metrics) = saved(RecordingType.Walk, hr = false)

        assertTrue("distance" in metrics && "moving_time" in metrics)
        assertNull(metrics["avg_hr"])
    }
}

/** history-screen/03: a saved recording reopened in review, read-only but deletable. */
class SummarySessionReviewTest {
    private val config = RunHarness.config

    private suspend fun savedStore(): InMemoryRecordingStore {
        val store = InMemoryRecordingStore()
        store.seedComputing(
            RecordingData(
                recording = Recording(id = config.recordingId, type = config.type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(60)),
                hr = (6..60).map { HrSample(at(it), 150) },
                fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
                events = listOf(RecordingEvent(at(120), EventKind.CooldownEnd, EventSource.Auto)),
                lastPersistedAt = at(120),
            ),
        )
        val fresh = SummarySession(config.recordingId, store, store, config.profile)
        fresh.load(); fresh.edit(SummaryEdits(RunTag.Long, rpe = 6, notes = "hills")); fresh.save()
        return store
    }

    @Test
    fun a_saved_recording_loads_with_the_edits_it_was_saved_with() = runTest {
        val store = savedStore()
        val review = SummarySession(config.recordingId, store, store, config.profile, review = true)

        review.load()

        assertIs<SummarySession.State.Ready>(review.state.value)
        assertEquals(SummaryEdits(RunTag.Long, rpe = 6, notes = "hills"), review.edits.value, "not the suggestion")
    }

    @Test
    fun save_and_the_hrmax_accept_change_nothing() = runTest {
        val store = savedStore()
        val before = store.records.metrics(config.recordingId)
        val review = SummarySession(config.recordingId, store, store, config.profile, review = true)
        review.load()
        val ready = review.state.value

        review.edit(SummaryEdits(RunTag.Easy, rpe = 1, notes = null)); review.save(); review.acceptHrMax()

        assertEquals(ready, review.state.value, "no busy flash, no Saved")
        assertEquals(RunTag.Long, assertNotNull(store.load(config.recordingId)).recording.tag)
        assertEquals(before, store.records.metrics(config.recordingId))
    }

    @Test
    fun delete_removes_the_saved_recording() = runTest {
        val store = savedStore()
        val review = SummarySession(config.recordingId, store, store, config.profile, review = true)
        review.load()

        review.delete()

        assertIs<SummarySession.State.Deleted>(review.state.value)
        assertNull(store.load(config.recordingId))
    }

    /** A row still on a stale list: the recording went meanwhile, so the delete has nothing left to do. */
    @Test
    fun a_delete_that_finds_the_recording_already_gone_counts_as_deleted() = runTest {
        val store = savedStore()
        val review = SummarySession(config.recordingId, store, store, config.profile, review = true)
        review.load()
        store.delete(config.recordingId)

        review.delete()

        assertIs<SummarySession.State.Deleted>(review.state.value)
    }

    /** Story 25: a reopened recording that cannot be computed is Failed, and Delete still removes it. */
    @Test
    fun a_reopened_recording_that_cannot_be_computed_is_failed_and_can_be_deleted() = runTest {
        val store = savedStore()
        val broken = object : RecordingStore by store {
            override suspend fun load(id: RecordingId) = store.load(id)?.let { it.copy(recording = it.recording.copy(endUtc = null)) } // no stop: nothing to compute
        }
        val review = SummarySession(config.recordingId, broken, store, config.profile, review = true)

        review.load()
        assertIs<SummarySession.State.Failed>(review.state.value)
        review.delete()

        assertIs<SummarySession.State.Deleted>(review.state.value)
        assertNull(store.load(config.recordingId))
    }
}
