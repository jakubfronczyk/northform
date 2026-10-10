package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.engine.run.RunSummary
import com.jakubfronczyk.northform.engine.run.saveRunSummary
import com.jakubfronczyk.northform.persistence.SqlRecordingStore
import com.jakubfronczyk.northform.persistence.inMemoryDriver
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.seedComputing
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** engine/04 end to end on run-1-park: stop → cool-down → computing → summary → saved rows → deleted. */
class SummaryOnRealRecordingsTest {

    private fun computed(name: String): Pair<RunHarness, RunSummary> {
        val script = bundled(name)
        val run = RunHarness.play(script.meta.start, script.runInputs)
        assertEquals(RecordingState.Computing, run.stored.recording.state)
        return run to RunSummary.compute(run.stored, RunHarness.config.profile)
    }

    @Test
    fun run_one_summary_is_filled_from_the_store_and_agrees_with_the_live_run() {
        val (run, summary) = computed("run-1-park")
        assertTrue(abs(summary.distance - 4829.7) < 0.5, "${summary.distance} m: the pinned live distance (D111)")
        assertEquals(run.state.snapshot.distance, summary.distance)
        assertEquals(run.stored.events.filter { it.kind is EventKind.Split }.map { it.t }, summary.splits.filter { !it.partial }.map { it.endAt }, "splits == live split events")
        assertEquals(run.state.snapshot.totalTime, summary.totalTime)
        // The metrics/01 pins (308 954 ms of Z0 …) counted from the Start TAP; the store holds samples from the run's
        // start (tap + 5 s countdown), so the summary's Z0 is those 5 s shorter. Z1–Z5 are the same to the millisecond.
        assertEquals(listOf<Long>(303_973, 278_106, 580_272, 1_086_670, 190_918, 0), summary.timeInZone.values.map { it.inWholeMilliseconds })
        assertEquals(RunTag.Tempo, summary.suggestedTag)
        assertTrue(summary.hrMax!! <= run.state.snapshot.hrMax!!, "the live max counted the cool-down too")
        assertNotNull(summary.hrAvg)
        assertTrue(summary.ascent > 0 && summary.descent > 0)
        // Run 1 as recorded: one Bluetooth drop, 84 s with no heart rate, GPS lost twice (the stairwell start).
        assertEquals(RunSummary.DataQuality(sensorDrops = 1, hrGap = 84.189.seconds, gpsLosses = 2, crashes = 0), summary.quality)
    }

    /** Characterisation pins of the headline numbers (no Swift reference), 2026-10-10. Recovery is null: run 1 skipped its cool-down. */
    @Test
    fun run_one_headline_numbers_stay_put() {
        val (_, summary) = computed("run-1-park")
        assertEquals("hrAvg ${summary.hrAvg} hrMax ${summary.hrMax} recovery ${summary.hrRecovery} pace ${summary.avgPace?.toInt()} paused ${summary.pausedTime.inWholeSeconds} climb +${summary.ascent.toInt()} -${summary.descent.toInt()} hrMaxSuggestion ${summary.hrMaxSuggestion}", PIN_RUN1)
    }

    private val PIN_RUN1 = "hrAvg 142 hrMax 172 recovery null pace 492 paused 154 climb +44 -45 hrMaxSuggestion null"

    @Test
    fun save_writes_the_rows_to_the_sql_store_and_delete_removes_them() = runTest {
        val (run, summary) = computed("run-1-park")
        val driver = inMemoryDriver()
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            store.seedComputing(run.stored)
            store.saveRunSummary(summary, summary.suggestedTag, 6, null)
            val saved = assertNotNull(store.load(run.stored.recording.id))
            assertEquals(RecordingState.Saved, saved.recording.state)
            assertEquals(RunTag.Tempo, saved.recording.tag)
            val rows = store.metrics(run.stored.recording.id)
            assertEquals(summary.metrics(6).sortedBy { it.metricId.value }, rows.sortedBy { it.metricId.value }, "every row read back as written, profile included")
            assertTrue(rows.any { it.metricId == RecordingMetrics.Ids.sRpe })
            store.delete(run.stored.recording.id)
            assertNull(store.load(run.stored.recording.id))
            assertEquals(emptyList(), store.metrics(run.stored.recording.id))
        } finally {
            driver.close()
        }
    }
}
