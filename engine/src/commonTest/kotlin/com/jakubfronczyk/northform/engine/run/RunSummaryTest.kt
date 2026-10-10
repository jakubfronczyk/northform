package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.runSynchronously
import com.jakubfronczyk.northform.testing.seedComputing
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** engine/04: the summary built from the stored recording once the run is in `computing`. */
class RunSummaryTest {
    private val profile = RunHarness.config.profile

    /**
     * Active from 5 s: runs north 3 m/s with HR 150, pauses 30–40 s (HR 120 while paused: counted, like
     * live), stops at 60 s, HR 180 through the full cool-down (ends at 120 s), then ticks until computing.
     */
    private fun played(skipCooldown: Boolean = false): RunHarness {
        val run = activeRun(standingFix = true)
        var north = 0.0
        for (s in 6..60) {
            val paused = s in 31..40
            if (!paused) north += 3.0
            if (s == 30) run.send(RunInput.User(UserAction.Pause, at(30)))
            if (s == 40) run.send(RunInput.User(UserAction.Resume, at(40)))
            run.send(RunInput.Fix(fix(s, north = north)))
            run.send(RunInput.Hr(HrSample(at(s), if (paused) 120 else 150)))
            run.send(RunInput.Tick(at(s)))
        }
        run.send(RunInput.User(UserAction.Stop, at(60)))
        if (skipCooldown) run.send(RunInput.User(UserAction.SkipCooldown, at(61)))
        for (s in 61..126) {
            if (s <= 120) run.send(RunInput.Hr(HrSample(at(s), 180)))
            run.send(RunInput.Tick(at(s)))
        }
        run.drain()
        assertEquals(RecordingState.Computing, run.stored.recording.state, "the fixture reaches computing")
        return run
    }

    @Test
    fun avg_and_max_heart_rate_exclude_the_cooldown() {
        val run = played()
        val summary = RunSummary.compute(run.stored, profile)
        assertEquals(180, run.state.snapshot.hrMax, "the live snapshot kept counting through the cool-down")
        assertEquals(150, summary.hrMax)
        // 55 samples 6..60: 45 × 150 + 10 × 120 → 144.5 → 145 (half away from zero)
        assertEquals(145, summary.hrAvg)
        assertEquals(-30, summary.hrRecovery, "150 at stop, 180 a minute later")
    }

    @Test
    fun times_distance_and_pace_come_from_the_stored_streams() {
        val run = played()
        val summary = RunSummary.compute(run.stored, profile)
        assertEquals(55.seconds, summary.totalTime)
        assertEquals(10.seconds, summary.pausedTime)
        assertEquals(45.seconds, summary.activeTime)
        assertEquals(run.state.snapshot.distance, summary.distance, "D111: the number the run screen showed")
        assertTrue(summary.distance > 100, "${summary.distance} m")
        assertTrue(abs(summary.avgPace!! - 45.0 / summary.distance * 1000) < 1e-9)
        assertEquals(1, summary.splits.size)
        assertTrue(summary.splits.single().partial)
        assertEquals(RunTag.Easy, summary.suggestedTag)
        assertNull(summary.hrMaxSuggestion)
        assertEquals(RunSummary.DataQuality(sensorDrops = 0, hrGap = 0.seconds, gpsLosses = 0, crashes = 0), summary.quality)
    }

    @Test
    fun a_skipped_cooldown_has_no_recovery() {
        val summary = RunSummary.compute(played(skipCooldown = true).stored, profile)
        assertNull(summary.hrRecovery)
        assertNull(summary.metrics(null).firstOrNull { it.metricId == RecordingMetrics.Ids.hrRecovery })
    }

    @Test
    fun metrics_carry_the_profile_and_the_rpe_row_only_when_rated() {
        val summary = RunSummary.compute(played().stored, profile)
        val unrated = summary.metrics(null)
        val rated = summary.metrics(7)
        assertNull(unrated.firstOrNull { it.metricId == RecordingMetrics.Ids.sRpe })
        assertEquals(7 * 0.75, assertNotNull(rated.firstOrNull { it.metricId == RecordingMetrics.Ids.sRpe }).value, "7 × 45 s")
        assertTrue(rated.all { it.profile == profile })
        assertEquals((0..5).map { RecordingMetrics.Ids.timeInZone(it) } + RecordingMetrics.Ids.trimp + RecordingMetrics.Ids.sRpe + RecordingMetrics.Ids.hrRecovery, rated.map { it.metricId })
    }

    /** Save writes the rows and finishes the recording; Delete removes everything, rows included. */
    @Test
    fun save_stores_the_metric_rows_and_delete_removes_them() {
        val data = played().stored
        val summary = RunSummary.compute(data, profile)
        val store = InMemoryRecordingStore()
        runSynchronously {
            store.seedComputing(data)
            store.saveRunSummary(summary, RunTag.Tempo, 7, "felt smooth")
        }
        val saved = assertNotNull(store.records.load(data.recording.id)).recording
        assertEquals(RecordingState.Saved, saved.state)
        assertEquals(Triple(RunTag.Tempo, 7, "felt smooth"), Triple(saved.tag, saved.rpe, saved.notes))
        assertEquals(summary.metrics(7), store.records.metrics(data.recording.id))
        runSynchronously { store.delete(data.recording.id) }
        assertNull(store.records.load(data.recording.id))
        assertEquals(emptyList(), store.records.metrics(data.recording.id))
    }
}
