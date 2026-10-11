package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState.Active
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** R28 crash resume: the gap the resumed run carries (the prompt rule is `CrashPromptTest`). */
class CrashResumeTest {
    private val config = RunHarness.config

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

    /**
     * Backgrounded at 65.6 s, after a live tick at 64.9 s: the store holds no HrLost, but rebuild's own
     * tick at 65 s derives it. That loss is not durable yet, so Continue must still write it, or a later
     * HrBack closes a loss the store never opened and reliability misses the gap.
     */
    @Test
    fun a_loss_rebuild_derives_after_the_last_stored_event_is_still_written_on_continue() {
        val rebuilt = RunReducer.rebuild(config, stored().copy(lastPersistedAt = at(65.6)))
        assertTrue(rebuilt.progress.hrLost)
        val resumed = RunReducer.resumed(rebuilt, at = at(362))
        assertEquals(
            listOf(
                RecordingEvent(at(65), EventKind.HrLost, EventSource.Auto),
                RecordingEvent(at(70.6), EventKind.GpsLost, EventSource.Auto), // the catch-up ticks run from the heartbeat
                RecordingEvent(at(362), EventKind.CrashRecovered, EventSource.Auto),
            ),
            resumed.buffer.events,
        )
    }

    @Test
    fun a_loss_already_stored_is_not_written_twice() {
        val withLoss = stored(events = listOf(RecordingEvent(at(65), EventKind.HrLost, EventSource.Auto))).copy(lastPersistedAt = at(65.6))
        val resumed = RunReducer.resumed(RunReducer.rebuild(config, withLoss), at = at(362))
        assertEquals(listOf(EventKind.GpsLost, EventKind.CrashRecovered), resumed.buffer.events.map { it.kind })
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
