package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingState.Active
import com.jakubfronczyk.northform.core.RecordingState.Computing
import com.jakubfronczyk.northform.core.RecordingState.Stopping
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.CrashChoice.Continue
import com.jakubfronczyk.northform.engine.run.CrashChoice.Delete
import com.jakubfronczyk.northform.engine.run.CrashChoice.Save
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** R28 crash resume: the prompt rule (Swift D75 restated) and the gap the resumed run carries. */
class CrashResumeTest {
    private val config = RunHarness.config

    // ── The prompt rule ───────────────────────────────────────────────────────────────────

    @Test
    fun the_prompt_offers_continue_first_only_for_a_fresh_active_run() {
        data class Row(val state: RecordingState, val gap: Duration, val choices: List<CrashChoice>, val main: CrashChoice)
        val table = listOf(
            Row(Active, 0.seconds, listOf(Continue, Save, Delete), Continue),
            Row(Active, 4.minutes, listOf(Continue, Save, Delete), Continue),
            Row(Active, 10.minutes - 1.seconds, listOf(Continue, Save, Delete), Continue),
            Row(Active, 10.minutes, listOf(Save, Continue, Delete), Save), // `CrashPromptLate`: at the limit Save leads
            Row(Active, 40.minutes, listOf(Save, Continue, Delete), Save),
            Row(Stopping, 1.minutes, listOf(Save, Delete), Save), // hold-to-stop happened: the run is over
            Row(Computing, 1.minutes, listOf(Save, Delete), Save), // every write was done
        )
        for (row in table) {
            val prompt = CrashPrompt.of(row.state, row.gap)
            assertEquals(row.choices, prompt.choices, "$row")
            assertEquals(row.main, prompt.main, "$row")
        }
        assertFailsWith<IllegalStateException> { CrashPrompt.of(RecordingState.Saved, 1.minutes) }
    }

    // ── The gap ────────────────────────────────────────────────────────────────────────────

    /** A run that started at 5 s, had HR and GPS until 60 s, and was last flushed at 62 s. */
    private fun stored(events: List<RecordingEvent> = emptyList()) = RecordingData(
        recording = Recording(id = config.recordingId, type = config.type, state = Active, startUtc = at(5), tzOffset = config.tzOffset),
        hr = (6..60).map { HrSample(at(it), 130) },
        fixes = (6..60).map { fix(it, north = (it - 5) * 3.0) },
        events = events,
        lastPersistedAt = at(62),
    )

    @Test
    fun the_gap_until_reopen_counts_as_hr_lost_and_gps_lost_and_total_time_keeps_running() {
        val resumed = RunReducer.resumed(RunReducer.rebuild(config, stored()), at = at(362))

        assertEquals(RunPhase.Active, resumed.progress.phase)
        assertEquals(357.seconds, resumed.snapshot.totalTime, "5 s → 362 s, the gap included")
        assertTrue(resumed.snapshot.hrLost && resumed.snapshot.gpsLost)
        assertEquals(
            listOf(
                RecordingEvent(at(65), EventKind.HrLost, EventSource.Auto), // 5 s after the last sample, not at reopen
                RecordingEvent(at(70), EventKind.GpsLost, EventSource.Auto), // 10 s after the last fix
                RecordingEvent(at(362), EventKind.CrashRecovered, EventSource.Auto),
            ),
            resumed.buffer.events,
        )
        assertTrue(resumed.progress.hrLostAlerted, "the HR-lost alert for this gap is spent: nothing vibrates on resume")
        assertEquals(emptyList(), resumed.buffer.hr, "nothing already durable is buffered again")
        assertNull(resumed.buffer.inFlight, "nothing was written during the catch-up")
        assertNull(resumed.buffer.flushRequestedAt)
        assertEquals(at(62), resumed.buffer.lastPersistAt, "the old heartbeat, so the first live tick flushes")

        // The first live tick persists the gap's events at once.
        val (_, effects) = RunReducer.reduce(resumed, RunInput.Tick(at(363)))
        val persist = effects.filterIsInstance<RunEffect.Persist>().single()
        assertEquals(listOf(EventKind.HrLost, EventKind.GpsLost, EventKind.CrashRecovered), persist.batch.events.map { it.kind })
        assertEquals(at(363), persist.batch.persistedAt)
    }

    @Test
    fun a_run_paused_at_the_crash_stays_paused_and_its_active_time_does_not_grow() {
        val paused = stored(events = listOf(RecordingEvent(at(50), EventKind.Pause, EventSource.Manual)))
        val rebuilt = RunReducer.rebuild(config, paused)
        val resumed = RunReducer.resumed(rebuilt, at = at(600))

        assertEquals(RunPhase.Paused(since = at(50)), resumed.progress.phase)
        assertEquals(595.seconds, resumed.snapshot.totalTime)
        assertEquals(45.seconds, resumed.progress.activeTime(at(600)), "paused since 50 s: active time stopped there")
        assertEquals(EventKind.CrashRecovered, resumed.buffer.events.last().kind)
    }

    @Test
    fun reopening_before_the_heartbeat_only_records_the_recovery() {
        val resumed = RunReducer.resumed(RunReducer.rebuild(config, stored()), at = at(61))
        assertEquals(listOf(RecordingEvent(at(61), EventKind.CrashRecovered, EventSource.Auto)), resumed.buffer.events)
        assertEquals(at(62), resumed.buffer.lastPersistAt)
    }
}
