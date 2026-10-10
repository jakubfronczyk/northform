package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayLocation
import com.jakubfronczyk.northform.replay.ReplaySensor
import com.jakubfronczyk.northform.replay.ReplayTimeline
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The LIVE branch of a run, which only the phone exercised before: permissions are asked before the
 * keep-alive is held, the keep-alive is held once the session runs, and it is released once the run is
 * over — whichever way it ended. The ports are the replay ones; the source is `RunSource.Live`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRunTest {
    private val script = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    private class Trace {
        val events = ArrayList<String>()
        val keepAlive = object : RunKeepAlive {
            override fun hold() { events += "hold" }
            override fun release() { events += "release" }
        }
        val permissions: suspend () -> Unit = { events += "permissions" }
    }

    private fun kotlinx.coroutines.test.TestScope.liveDeps(trace: Trace, log: (String) -> Unit = {}): AppDeps {
        val clock = virtualWallClock(script.meta.start)
        val timeline = ReplayTimeline(script, clock) // never played: the live branch, not the fixture, is under test
        return AppDeps(
            source = RunSource.Live(ReplaySensor(timeline), ReplayLocation(timeline), trace.keepAlive, trace.permissions),
            store = InMemoryRecordingStore(), wallClock = clock, alerts = RecordedAlerts(), log = log,
            newRecordingId = { "00000000-0000-0000-0000-00000000000b" },
        )
    }

    @Test
    fun permissions_then_session_then_keep_alive_then_release_on_close() = runTest {
        val trace = Trace()
        val log = ArrayList<String>()
        val run = ActiveRun.open(liveDeps(trace) { log += it }, backgroundScope)
        runCurrent()
        assertEquals(listOf("permissions", "hold"), trace.events, "the prompt comes before the keep-alive, the keep-alive after the session exists")
        assertTrue(log.first().startsWith("session start"), "$log")
        assertEquals(RunPhase.Gate, run.session.snapshots.value.phase)

        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()
        assertEquals(RunPhase.Active, run.session.snapshots.value.phase)

        run.session.tap(UserAction.Stop); runCurrent()
        run.session.tap(UserAction.SkipCooldown); runCurrent()
        val closing = async { run.close() }
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(EndResult.Finishing, closing.await())
        assertEquals(listOf("permissions", "hold", "release"), trace.events, "released exactly once, after the run's last write")
        assertTrue(run.runJob.isCompleted)
    }

    @Test
    fun closing_at_the_gate_discards_and_still_releases_the_keep_alive() = runTest {
        val trace = Trace()
        val run = ActiveRun.open(liveDeps(trace), backgroundScope)
        runCurrent()
        val closing = async { run.close() }
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(EndResult.Discarded, closing.await())
        assertEquals(listOf("permissions", "hold", "release"), trace.events)
    }

    @Test
    fun backgrounded_reaches_the_session_without_ending_the_run() = runTest {
        val trace = Trace()
        val run = ActiveRun.open(liveDeps(trace), backgroundScope)
        runCurrent()
        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()
        run.backgrounded(run.session.wallClock.now()); runCurrent()
        assertEquals(RunPhase.Active, run.session.snapshots.value.phase)
        assertFalse(run.runJob.isCompleted)
        assertFalse("release" in trace.events)
    }
}
