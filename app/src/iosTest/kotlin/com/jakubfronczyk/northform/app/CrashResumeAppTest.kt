package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.engine.run.CrashChoice
import com.jakubfronczyk.northform.engine.run.CrashPrompt
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.persistence.SqlRecordingStore
import com.jakubfronczyk.northform.persistence.inMemoryDriver
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayScript
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * R28 on the store the phone boots with: a replay run is killed mid-way on `SqlRecordingStore`, the next
 * launch finds it, and each answer of the prompt does what it says. The kill is a cancelled scope — the
 * database outlives it, as the file outlives a dead process — and a second `AppModel` is the relaunch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashResumeAppTest {
    private fun straightRun(): ReplayScript = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    private fun TestScope.replayDeps(script: ReplayScript, store: RecordingStore, clockStart: Instant = script.meta.start, log: (String) -> Unit = {}) = AppDeps(
        source = RunSource.Replay { script },
        store = store,
        wallClock = virtualWallClock(clockStart),
        alerts = RecordedAlerts(),
        log = log,
        newRecordingId = { "00000000-0000-0000-0000-00000000000c" },
    )

    /** A child scope of the test's background scope: cancelling it is the crash; the test's clock keeps running. */
    private fun TestScope.process() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))

    @Test
    fun a_run_killed_mid_way_is_found_and_continues_with_no_flushed_data_lost_and_the_gap_stored() = runTest {
        val script = straightRun()
        val driver = inMemoryDriver("crash-resume-continue")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)

            // Launch 1: a replay run, 46 s in, then the process dies.
            val process = process()
            val first = AppModel(replayDeps(script, store), process)
            first.startRun(); runCurrent()
            val run = assertNotNull(first.activeRun.value)
            run.session.tap(UserAction.Start)
            advanceTimeBy(46.seconds); runCurrent() // HR and GPS both flowing here (the fixture scripts a sensor drop at 60 s)
            val live = run.session.snapshots.value
            assertEquals(RunPhase.Active, live.phase)
            process.cancel(); runCurrent()
            val flushed = assertNotNull(store.load(run.id), "the run is on disk")
            assertEquals(RecordingState.Active, flushed.recording.state)
            assertTrue(flushed.hr.size > 25 && flushed.fixes.size > 25, "${flushed.hr.size} hr, ${flushed.fixes.size} fixes were flushed before the kill")
            val heartbeat = assertNotNull(flushed.lastPersistedAt)

            advanceTimeBy(5.minutes) // the app is dead

            // Launch 2: boot finds the recording; nothing runs until the user chooses (D9).
            val log = ArrayList<String>()
            val second = AppModel(replayDeps(script, store) { log += it }, backgroundScope)
            second.checkUnfinished(); runCurrent()
            val unfinished = assertNotNull(second.unfinished.value, "the prompt is up")
            assertNull(second.activeRun.value, "nothing resumed on its own")
            val facts = unfinished.facts
            assertEquals(run.id, unfinished.id)
            assertEquals(RecordingState.Active, facts.state)
            assertEquals(heartbeat, facts.savedUntil)
            assertEquals(live.startedAt, facts.startedAt)
            val reopen = virtualWallClock(script.meta.start).now()
            assertTrue(facts.interruptedFor(reopen) >= 5.minutes && facts.interruptedFor(reopen) < 6.minutes, "${facts.interruptedFor(reopen)}")
            assertTrue(facts.distance > 100 && facts.distance <= live.distance, "what is saved: ${facts.distance} m of ${live.distance} m")
            assertEquals(CrashChoice.Continue, CrashPrompt.of(facts, reopen).main, "< 10 min: Continue leads")

            // Continue: the same run, the gap counted as lost, total time including it.
            second.resolveUnfinished(CrashChoice.Continue); runCurrent()
            val resumed = assertNotNull(second.activeRun.value)
            assertNull(second.unfinished.value)
            assertEquals(run.id, resumed.id)
            val snapshot = resumed.session.snapshots.value
            assertEquals(RunPhase.Active, snapshot.phase)
            assertEquals(live.startedAt, snapshot.startedAt)
            assertTrue(snapshot.totalTime >= live.totalTime + 5.minutes, "total time kept running: ${snapshot.totalTime}")
            assertTrue(snapshot.hrLost && snapshot.gpsLost, "the gap is HR lost + GPS lost")
            assertTrue(log.any { "resumed, R28" in it }, "$log")

            advanceTimeBy(2.seconds); runCurrent() // the first live tick flushes the gap's events
            val after = assertNotNull(store.load(run.id))
            assertEquals(flushed.hr, after.hr.take(flushed.hr.size), "nothing flushed before the kill was lost or reordered")
            assertEquals(flushed.fixes, after.fixes.take(flushed.fixes.size))
            val gap = after.events.drop(flushed.events.size).map { it.kind }.take(5) // the replay sensor may already be back behind them
            assertEquals(listOf(EventKind.Pause, EventKind.HrLost, EventKind.GpsLost, EventKind.Resume, EventKind.CrashRecovered), gap) // D40: the gap is a pause
            // Lost at the first whole-second tick 5 s / 10 s after the last stored sample, not at reopen (samples sit at x.5 s).
            val hrLostAfter = after.events.first { it.kind == EventKind.HrLost }.t - flushed.hr.last().t
            assertTrue(hrLostAfter >= 5.seconds && hrLostAfter < 6.seconds, "HR lost $hrLostAfter after the last stored sample")
            val gpsLostAfter = after.events.first { it.kind == EventKind.GpsLost }.t - flushed.fixes.last().t
            assertTrue(gpsLostAfter >= 10.seconds && gpsLostAfter < 11.seconds, "GPS lost $gpsLostAfter after the last stored fix")

            advanceTimeBy(30.seconds); runCurrent() // the replay sensor is back: the run goes on
            val later = assertNotNull(store.load(run.id))
            assertTrue(later.hr.size > after.hr.size, "new samples are stored after the resume")
            assertTrue(later.events.any { it.kind == EventKind.HrBack })
            assertFalse(resumed.session.snapshots.value.hrLost)
        } finally {
            driver.close()
        }
    }

    // ── Save and Delete on a recording seeded as a crash left it ──────────────────────────

    private val t0 = Instant.parse("2026-10-10T06:00:00Z")
    private val seeded = RecordingId("crashed-1")

    private suspend fun seed(store: RecordingStore, stopped: Boolean = false) {
        store.create(Recording(id = seeded, type = RecordingType.Run, state = RecordingState.Active, startUtc = t0, tzOffset = 7200))
        store.flush(FlushBatch(seeded, hr = (1..30).map { HrSample(t0 + it.seconds, 140) }, fixes = (1..30).map { LocationFix(t0 + it.seconds, 52.0 + it * 2e-5, 21.0, 5.0) }, persistedAt = t0 + 30.seconds))
        if (stopped) store.stop(seeded, t0 + 31.seconds)
    }

    @Test
    fun save_ends_the_run_at_the_last_heartbeat_not_at_reopen_and_hands_it_to_computing() = runTest {
        val driver = inMemoryDriver("crash-resume-save")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(store)
            val deps = replayDeps(straightRun(), store, clockStart = t0 + 3.hours) // reopened 3 h later: the gap is not the run's
            val model = AppModel(deps, backgroundScope)
            model.checkUnfinished(); runCurrent()
            val facts = assertNotNull(model.unfinished.value).facts
            assertEquals(t0 + 30.seconds, facts.savedUntil)
            assertEquals(30.seconds, facts.totalTime)

            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertNull(model.unfinished.value)
            val saved = assertNotNull(store.load(seeded))
            assertEquals(RecordingState.Computing, saved.recording.state, "where a normally closed run sits (engine/04 picks it up)")
            assertEquals(t0 + 30.seconds, saved.recording.endUtc, "the run ends where the store last heard from it, not at reopen")
            assertEquals(EventKind.Stop, saved.events.last().kind)
            assertEquals(30, saved.hr.size)
            assertNull(model.activeRun.value, "Save opens no run")
            // D32: the crash leaves a mark on a saved run too, and the heartbeat ("saved until") does not move.
            assertEquals(listOf(RecordingEvent(t0 + 3.hours, EventKind.CrashRecovered, EventSource.Auto)), saved.events.filter { it.kind == EventKind.CrashRecovered })
            assertEquals(t0 + 30.seconds, saved.lastPersistedAt)
        } finally {
            driver.close()
        }
    }

    @Test
    fun a_recording_killed_after_stop_offers_no_continue_and_save_only_moves_it_to_computing() = runTest {
        val driver = inMemoryDriver("crash-resume-stopping")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(store, stopped = true)
            val model = AppModel(replayDeps(straightRun(), store), backgroundScope)
            model.checkUnfinished(); runCurrent()
            val facts = assertNotNull(model.unfinished.value).facts
            assertEquals(RecordingState.Stopping, facts.state)
            assertEquals(listOf(CrashChoice.Save, CrashChoice.Delete), CrashPrompt.of(facts, t0 + 1.minutes).choices)

            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            val saved = assertNotNull(store.load(seeded))
            assertEquals(RecordingState.Computing, saved.recording.state)
            assertEquals(t0 + 31.seconds, saved.recording.endUtc, "the stop that was written stands")
        } finally {
            driver.close()
        }
    }

    // ── D30: the interruption is measured at the tap ─────────────────────────────────────

    @Test
    fun continue_tapped_2_h_after_the_last_heartbeat_is_refused_though_the_prompt_was_built_at_5_min() = runTest {
        val driver = inMemoryDriver("crash-resume-continue-cap")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(store) // heartbeat at t0 + 30 s
            val heartbeat = t0 + 30.seconds
            val log = ArrayList<String>()
            val model = AppModel(replayDeps(straightRun(), store, clockStart = heartbeat + 5.minutes) { log += it }, backgroundScope)
            model.checkUnfinished(); runCurrent()
            val facts = assertNotNull(model.unfinished.value).facts
            assertEquals(CrashChoice.Continue, CrashPrompt.of(facts, heartbeat + 5.minutes).main, "built at 5 min: Continue leads")

            advanceTimeBy(2.hours - 5.minutes - 1.seconds); runCurrent() // 1:59:59, the prompt still up
            assertTrue(CrashChoice.Continue in CrashPrompt.of(facts, heartbeat + 2.hours - 1.seconds).choices)
            advanceTimeBy(1.seconds); runCurrent() // 2:00:00
            model.resolveUnfinished(CrashChoice.Continue); runCurrent()
            assertNull(model.activeRun.value, "past 2 h nothing is continued, however late the tap")
            assertNotNull(model.unfinished.value, "the prompt stays up: Save or Delete")
            assertEquals(RecordingState.Active, assertNotNull(store.load(seeded)).recording.state, "nothing was written")
            assertTrue(log.any { "Continue refused" in it }, "$log")

            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertEquals(RecordingState.Computing, assertNotNull(store.load(seeded)).recording.state)
        } finally {
            driver.close()
        }
    }

    @Test
    fun delete_removes_the_recording_and_the_prompt() = runTest {
        val driver = inMemoryDriver("crash-resume-delete")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(store)
            val model = AppModel(replayDeps(straightRun(), store), backgroundScope)
            model.checkUnfinished(); runCurrent()
            assertNotNull(model.unfinished.value)

            model.resolveUnfinished(CrashChoice.Delete); runCurrent()
            assertNull(model.unfinished.value)
            assertNull(store.load(seeded))
            assertNull(store.unfinished())
        } finally {
            driver.close()
        }
    }

    /** The real store, except `beginComputing` fails the first time: a Save that gets halfway (stopped, not computing). */
    private fun failingBeginComputingOnce(real: RecordingStore) = object : RecordingStore by real {
        var failOnce = true
        override suspend fun beginComputing(id: RecordingId) {
            if (failOnce) { failOnce = false; error("disk full") }
            real.beginComputing(id)
        }
    }

    @Test
    fun a_save_retried_after_a_half_done_save_only_does_the_step_left() = runTest {
        val driver = inMemoryDriver("crash-resume-save-retry")
        try {
            val real = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(real)
            val model = AppModel(replayDeps(straightRun(), failingBeginComputingOnce(real)), backgroundScope)
            model.checkUnfinished(); runCurrent()
            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertNotNull(model.unfinished.value, "the first Save failed: the prompt stays")
            assertEquals(RecordingState.Stopping, assertNotNull(real.load(seeded)).recording.state, "halfway: stopped, not computing")

            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertNull(model.unfinished.value, "the retry succeeds: no second stop")
            val saved = assertNotNull(real.load(seeded))
            assertEquals(RecordingState.Computing, saved.recording.state)
            assertEquals(t0 + 30.seconds, saved.recording.endUtc)
        } finally {
            driver.close()
        }
    }

    /** The real store, except `stop` fails the first time: a Save that wrote its crash mark and got no further. */
    private fun failingStopOnce(real: RecordingStore) = object : RecordingStore by real {
        var failOnce = true
        override suspend fun stop(id: RecordingId, endUtc: Instant) {
            if (failOnce) { failOnce = false; error("disk full") }
            real.stop(id, endUtc)
        }
    }

    @Test
    fun a_save_retried_after_the_crash_mark_was_written_does_not_write_it_twice() = runTest {
        val driver = inMemoryDriver("crash-resume-save-retry-mark")
        try {
            val real = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(real)
            val model = AppModel(replayDeps(straightRun(), failingStopOnce(real), clockStart = t0 + 1.hours), backgroundScope)
            model.checkUnfinished(); runCurrent()
            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertNotNull(model.unfinished.value, "the first Save failed at stop: the prompt stays")
            assertEquals(RecordingState.Active, assertNotNull(real.load(seeded)).recording.state)

            advanceTimeBy(1.minutes)
            model.resolveUnfinished(CrashChoice.Save); runCurrent()
            assertNull(model.unfinished.value)
            val saved = assertNotNull(real.load(seeded))
            assertEquals(RecordingState.Computing, saved.recording.state)
            assertEquals(1, saved.events.count { it.kind == EventKind.CrashRecovered }, "${saved.events}")
            assertEquals(t0 + 30.seconds, saved.recording.endUtc)
        } finally {
            driver.close()
        }
    }

    @Test
    fun continue_on_a_recording_the_store_no_longer_holds_as_active_saves_it_instead() = runTest {
        val driver = inMemoryDriver("crash-resume-continue-stopped")
        try {
            val real = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(real)
            val model = AppModel(replayDeps(straightRun(), failingBeginComputingOnce(real)), backgroundScope)
            model.checkUnfinished(); runCurrent()
            model.resolveUnfinished(CrashChoice.Save); runCurrent() // halfway: the prompt still says Active (read at boot)
            assertEquals(RecordingState.Active, assertNotNull(model.unfinished.value).facts.state)

            model.resolveUnfinished(CrashChoice.Continue); runCurrent()
            assertNull(model.activeRun.value, "a stopped recording is never resumed")
            assertNull(model.unfinished.value)
            assertEquals(RecordingState.Computing, assertNotNull(real.load(seeded)).recording.state, "routed to Save")
        } finally {
            driver.close()
        }
    }

    @Test
    fun start_run_does_nothing_while_the_crash_prompt_is_up() = runTest {
        val driver = inMemoryDriver("crash-resume-start-blocked")
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            seed(store)
            val model = AppModel(replayDeps(straightRun(), store), backgroundScope)
            model.checkUnfinished(); runCurrent()
            assertNotNull(model.unfinished.value)

            model.startRun(); runCurrent()
            assertNull(model.activeRun.value, "the prompt owns the screen: answer it first")
            model.resolveUnfinished(CrashChoice.Delete); runCurrent()
            model.startRun(); runCurrent()
            assertNotNull(model.activeRun.value, "answered: Start works again")
        } finally {
            driver.close()
        }
    }

    @Test
    fun a_clean_database_shows_no_prompt() = runTest {
        val driver = inMemoryDriver("crash-resume-clean")
        try {
            val model = AppModel(replayDeps(straightRun(), SqlRecordingStore(driver, io = EmptyCoroutineContext)), backgroundScope)
            model.checkUnfinished(); runCurrent()
            assertNull(model.unfinished.value)
        } finally {
            driver.close()
        }
    }
}
