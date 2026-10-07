package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RecordingSession.EndResult
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.rebuild
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `Tests/EngineTests/RecordingSessionTests.swift`, case by case. The Swift `VirtualClock` (one time
 * for sleepers and the wall clock) is `runTest`'s `TestCoroutineScheduler`: `delay` runs on it, and
 * the [WallClock] reads `currentTime` off it, so the two can never disagree. No real time passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionLiveTest {


    /** `RecordingSessionTests.swift:47` — a live session on one virtual clock with the replay of `script`. */
    private inner class LiveRun(val scope: TestScope, script: ReplayScript) {
        val wallClock = scope.virtualWallClock(script.meta.start)
        val timeline = ReplayTimeline(script, wallClock)
        val store = InMemoryRecordingStore()
        val alerts = RecordedAlerts()
        val session = RecordingSession(RunHarness.config, ReplaySensor(timeline), ReplayLocation(timeline), store, alerts, wallClock)
        lateinit var running: Job

        /** Starts the session and the fixture's taps; `during` runs before the clock advances. */
        suspend fun play(seconds: Int, during: suspend () -> Unit = {}) {
            running = scope.launch { session.run() }
            val taps = scope.launch { timeline.userActions().collect { session.send(it.action, it.t) } }
            scope.runCurrent()
            val playing = scope.launch { timeline.play() }
            during()
            scope.advanceTimeBy(seconds.seconds)
            scope.runCurrent()
            running.join() // `run()` returns by itself after the last write; a hang here fails the test (runTest timeout)
            taps.cancel()
            playing.cancel()
        }

        val stored: RecordingData? get() = store.records.load(RunHarness.config.recordingId)
    }

    private fun straightRun() = bundled("straight-run-3min")
    private fun emptyScript() = ReplayScript(straightRun().meta, emptyList())

    /** `:16` — the live session, on one virtual clock with the replay, ends where the pure harness ends. */
    @Test
    fun session_on_a_virtual_clock_matches_the_pure_harness() = runTest {
        val script = straightRun()
        val live = LiveRun(this, script)
        live.play(260)

        val harness = RunHarness.play(script.meta.start, script.runInputs)
        val snapshot = live.session.snapshot
        assertEquals(RunPhase.Done, snapshot.phase)
        assertTrue(abs(snapshot.distance - harness.state.snapshot.distance) < 1e-6)
        assertEquals(harness.state.snapshot.totalTime, snapshot.totalTime)
        assertEquals(harness.state.snapshot.hrAvg, snapshot.hrAvg)

        val stored = assertNotNull(live.stored)
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertEquals(harness.stored.events, stored.events)
        assertEquals(harness.stored.hr, stored.hr)
        assertEquals(harness.alerts, live.alerts.played)
    }

    /** D94 through the live path: folding what the session persisted gives the state it showed. */
    @Test
    fun rebuild_from_what_the_live_session_persisted_matches_what_it_showed() = runTest {
        val live = LiveRun(this, straightRun())
        live.play(260)
        val rebuilt = RunReducer.rebuild(RunHarness.config, assertNotNull(live.stored)).snapshot
        val shown = live.session.snapshot
        assertEquals(shown.distance, rebuilt.distance)
        assertEquals(shown.hrAvg, rebuilt.hrAvg)
        assertEquals(shown.hrMax, rebuilt.hrMax)
        assertEquals(shown.totalTime, rebuilt.totalTime)
    }

    // ── Session lifetime (D112) ──────────────────────────────────────────────────────────────

    /** C1 (`:92`): Bluetooth permission withdrawn mid-run ends the HR stream, not the recording. */
    @Test
    fun hr_unauthorized_mid_run_does_not_end_the_session() = runTest {
        val base = straightRun()
        val cut = base.meta.start + 70.seconds
        val inputs = base.inputs.filterNot { it is ReplayInput.Hr && it.t >= cut } + ReplayInput.Sensor(SensorState.Unauthorized, cut)
        val live = LiveRun(this, ReplayScript(base.meta, inputs.sortedBy { it.t }))
        live.play(260)

        val stored = assertNotNull(live.stored)
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertTrue(stored.fixes.any { it.t > base.meta.start + 170.seconds }, "GPS kept counting")
        assertTrue(stored.events.any { it.kind == EventKind.CooldownEnd })
        assertTrue(live.session.snapshot.distance > 400)
    }

    /** C2 (`:112`): the run screen closes during the cool-down; the session still stores the cool-down HR and hands the run to computing. */
    @Test
    fun closing_the_screen_in_the_cooldown_keeps_persisting() = runTest {
        val base = straightRun()
        val cooldownHr = (190..240).map { ReplayInput.Hr(HrSample(base.meta.start + it.seconds + 500.milliseconds, 120)) }
        val live = LiveRun(this, ReplayScript(base.meta, (base.inputs + cooldownHr).sortedBy { it.t }))
        live.play(260) {
            launch { live.session.snapshots.first { it.phase is RunPhase.Cooldown } } // the screen's follower goes away here
        }
        val stored = assertNotNull(live.stored)
        val stop = assertNotNull(stored.recording.endUtc)
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertTrue(stored.events.any { it.kind == EventKind.CooldownEnd })
        assertTrue(stored.hr.any { it.t > stop }, "cool-down heart rate stored")
    }

    /** C3 (`:136`): a second `run()` returns at once; the first keeps persisting. */
    @Test
    fun second_run_returns_at_once_and_the_first_keeps_persisting() = runTest {
        val live = LiveRun(this, straightRun())
        live.play(260) {
            var secondReturned = false
            launch { live.session.run(); secondReturned = true }
            runCurrent()
            assertTrue(secondReturned, "returned before any time passed")
        }
        val stored = assertNotNull(live.stored)
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertTrue(stored.events.any { it.kind == EventKind.CooldownEnd })
    }

    /** `:157` — closing at the gate cancels the run task: nothing was recorded, nothing is created. */
    @Test
    fun cancel_at_the_gate_creates_no_recording() = runTest {
        val live = LiveRun(this, emptyScript())
        val running = launch { live.session.run() }
        runCurrent()
        running.cancel()
        running.join()
        assertNull(live.stored)
    }

    // ── Features seam (D110) ─────────────────────────────────────────────────────────────────

    /** `:175` — through `RunSessionHandle`: the current snapshot arrives first, and a tap keeps the time it was stamped with. */
    @Test
    fun session_works_through_the_screen_interface() = runTest {
        val live = LiveRun(this, emptyScript())
        val handle: RunSessionHandle = live.session
        val tappedAt = handle.wallClock.now() - 400.milliseconds // stamped before the hop
        val running = launch { live.session.run() }
        runCurrent()
        assertEquals(RunPhase.Gate, handle.snapshots.value.phase, "the current snapshot arrives first")
        handle.send(UserAction.Start, tappedAt)
        runCurrent()
        assertEquals(RunPhase.Countdown(endsAt = tappedAt + RunReducer.countdown), handle.snapshots.value.phase)
        running.cancel()
        running.join()
    }

    // ── Closing the run (D116) ───────────────────────────────────────────────────────────────

    /** `:195` — a session at the gate on a virtual clock, with no inputs; `body` drives it. `run()` must return by itself. */
    private suspend fun TestScope.atTheGate(body: suspend (RecordingSession) -> Unit): LiveRun {
        val live = LiveRun(this, emptyScript())
        val running = launch { live.session.run() }
        runCurrent()
        body(live.session)
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(running.isCompleted, "run() returned by itself")
        return live
    }

    @Test
    fun end_at_the_gate_discards_and_run_returns() = runTest {
        var result: EndResult? = null
        val live = atTheGate { result = it.end() }
        assertEquals(EndResult.Discarded, result)
        assertNull(live.stored)
    }

    /** `:229` — the race D116 closes: closing during the countdown, at the instant the tick that ends it fires. */
    @Test
    fun end_during_the_countdown_never_leaves_a_recording() = runTest {
        for (seconds in listOf(4.0, 4.999, 5.0)) {
            var result: EndResult? = null
            val live = atTheGate { session ->
                session.tap(UserAction.Start)
                advanceTimeBy(seconds.seconds)
                runCurrent()
                val r = session.end()
                result = r
                if (r == EndResult.Finishing) { // started: a finishing run ends at its last write, so finish it
                    session.tap(UserAction.Stop)
                    session.tap(UserAction.SkipCooldown)
                }
            }
            val stored = live.stored
            when (result) {
                EndResult.Discarded -> assertNull(stored, "discarded at $seconds s but a recording exists")
                EndResult.Finishing -> assertEquals(RecordingState.Computing, stored?.recording?.state)
                null -> error("end() never answered")
            }
            if (seconds < 5) assertEquals(EndResult.Discarded, result, "still in the countdown at $seconds s")
        }
    }

    /** `:253` — after start, closing changes nothing: stop, cool-down and computing are stored as before. */
    @Test
    fun end_while_active_keeps_the_run_going_to_its_last_write() = runTest {
        val live = LiveRun(this, straightRun())
        var results = emptyList<EndResult>()
        live.play(260) {
            advanceTimeBy(30.seconds) // running
            runCurrent()
            results = listOf(live.session.end(), live.session.end())
        }
        assertEquals(listOf(EndResult.Finishing, EndResult.Finishing), results, "a second end() gives the same answer")
        val stored = assertNotNull(live.stored)
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertTrue(stored.events.any { it.kind == EventKind.Stop })
        assertTrue(stored.events.any { it.kind == EventKind.CooldownEnd })
    }

    @Test
    fun second_end_at_the_gate_gives_the_same_answer() = runTest {
        var results = emptyList<EndResult>()
        val live = atTheGate { session ->
            val first = session.end()
            session.tap(UserAction.Start) // ignored: the run is dropped
            runCurrent()
            results = listOf(first, session.end())
        }
        assertEquals(listOf(EndResult.Discarded, EndResult.Discarded), results)
        assertNull(live.stored)
        assertEquals(RunPhase.Gate, live.session.snapshot.phase, "a dropped run takes no more input")
    }
}
