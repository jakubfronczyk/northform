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

        session.save(SummaryEdits(tag = RunTag.Tempo, rpe = 7, notes = "windy"))

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

        session.save(SummaryEdits(tag = null, rpe = 5, notes = null))

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

        session.save(SummaryEdits(tag = RunTag.Easy, rpe = 3, notes = null))

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

        val first = launch { session.save(SummaryEdits(tag = null, rpe = 6, notes = "first")) }
        runCurrent()
        assertTrue(assertIs<SummarySession.State.Ready>(session.state.value).busy, "Save and Delete are disabled while the store answers")
        session.save(SummaryEdits(tag = null, rpe = 9, notes = "second")) // the double tap
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

        session.save(SummaryEdits(tag = null, rpe = null, notes = null))
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
