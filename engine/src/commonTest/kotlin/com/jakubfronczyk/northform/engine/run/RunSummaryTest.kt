package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.core.ports.RecordingData
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
import kotlin.time.Duration
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
    fun the_distance_fold_counts_exactly_what_the_rebuild_counts() {
        for (run in listOf(played(), played(skipCooldown = true))) {
            val stored = run.stored
            assertEquals(RunReducer.rebuild(RunHarness.config, stored).progress.distance, RunReducer.distance(stored), "D111, bit for bit")
        }
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

    // ── The crash gap (R28 Continue): total time runs through it, average pace must not ──────────

    /**
     * Runs 0–60 s at 3 m/s with HR, the app dies after its 60 s flush, the user reopens at 360 s and taps
     * Continue (`resumed` writes HrLost 65, GpsLost 70 and CrashRecovered 360), runs 361–420 s and stops at 420.
     */
    private fun crashed(pauseAt: Int? = null, crashRecoveredAt: Int = 360, extra: List<RecordingEvent> = emptyList()): RecordingData {
        val start = at(0)
        val seconds = (1..60) + (361..420)
        val events = buildList {
            pauseAt?.let { add(RecordingEvent(at(it), EventKind.Pause, EventSource.Manual)) }
            add(RecordingEvent(at(65), EventKind.HrLost, EventSource.Auto))
            add(RecordingEvent(at(70), EventKind.GpsLost, EventSource.Auto))
            add(RecordingEvent(at(crashRecoveredAt), EventKind.CrashRecovered, EventSource.Auto))
            add(RecordingEvent(at(361), EventKind.HrBack, EventSource.Auto))
            add(RecordingEvent(at(361), EventKind.GpsBack, EventSource.Auto))
            pauseAt?.let { add(RecordingEvent(at(365), EventKind.Resume, EventSource.Manual)) }
            add(RecordingEvent(at(420), EventKind.Stop, EventSource.Manual))
            addAll(extra)
        }.sortedBy { it.t }
        return RecordingData(
            recording = Recording(RunHarness.config.recordingId, type = RecordingType.Run, state = RecordingState.Computing, startUtc = start, tzOffset = 0, endUtc = at(420)),
            hr = seconds.map { HrSample(at(it), 150) },
            fixes = listOf(fix(0, north = 0.0)) + seconds.mapIndexed { i, s -> fix(s, north = 3.0 * (i + 1)) },
            events = events,
            lastPersistedAt = at(425),
        )
    }

    @Test
    fun average_pace_leaves_out_the_crash_gap_that_total_time_keeps() {
        val summary = RunSummary.compute(crashed(), profile)
        assertEquals(420.seconds, summary.totalTime, "total time runs through the gap (Continue)")
        assertEquals(300.seconds, summary.crashGap, "from the last data before the crash (60 s) to the reopen (360 s)")
        assertEquals(120.seconds, summary.movingTime)
        assertTrue(summary.distance > 300, "${summary.distance} m")
        assertEquals(120.0 / summary.distance * 1000, assertNotNull(summary.avgPace), 1e-9)
    }

    @Test
    fun a_crash_while_paused_is_not_subtracted_twice() {
        // Paused at 50 s, the app died paused, the runner resumed at 365 s: the gap (60 → 360 s) is pause already.
        val summary = RunSummary.compute(crashed(pauseAt = 50), profile)
        assertEquals(315.seconds, summary.pausedTime)
        assertEquals(Duration.ZERO, summary.crashGap)
        assertEquals(105.seconds, summary.movingTime)
    }

    @Test
    fun a_crash_recorded_at_or_after_the_stop_takes_nothing_from_the_run() {
        // D32: Save after a crash writes CrashRecovered too; the run ends at its last heartbeat (D30), before it.
        val summary = RunSummary.compute(crashed(crashRecoveredAt = 500), profile)
        assertEquals(Duration.ZERO, summary.crashGap)
        assertEquals(summary.activeTime, summary.movingTime)
    }
}
