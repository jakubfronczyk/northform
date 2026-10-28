package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.LiveActivityEvent
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The LIVE branch of a run, which only the phone exercised before: permissions are asked before GPS is
 * collected (collecting is what holds the keep-alive, `LiveLocation`), and GPS is let go once the run is
 * over — whichever way it ended. The ports are the replay ones; the source is `RunSource.Live`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRunTest {
    private val script = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    private class Trace {
        val events = ArrayList<String>()
        val permissions: suspend () -> Unit = { events += "permissions" }

        /** The location port as the session sees it, writing when it is collected and let go. */
        fun traced(location: LocationProvider) = object : LocationProvider {
            override fun fixes(): Flow<LocationFix> =
                location.fixes().onStart { events += "collect" }.onCompletion { events += "cancel" }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.liveDeps(
        trace: Trace,
        log: (String) -> Unit = {},
        liveActivity: (LiveActivityEvent) -> Unit = {},
    ): AppDeps {
        val clock = virtualWallClock(script.meta.start)
        val timeline = ReplayTimeline(script, clock) // never played: the live branch, not the fixture, is under test
        val store = InMemoryRecordingStore()
        return AppDeps(
            source = RunSource.Live(ReplaySensor(timeline), trace.traced(ReplayLocation(timeline)), trace.permissions),
            store = store, reliability = store, wallClock = clock, alerts = RecordedAlerts(), log = log,
            newRecordingId = { "00000000-0000-0000-0000-00000000000b" },
            liveActivity = liveActivity,
        )
    }

    /** D11: the lock screen gets Start when the run starts, throttled updates, and exactly one End — whichever way the run ends. */
    @Test
    fun live_activity_starts_with_the_run_and_ends_once_when_it_stops() = runTest {
        val events = ArrayList<LiveActivityEvent>()
        val run = ActiveRun.open(liveDeps(Trace(), liveActivity = { events += it }), backgroundScope)
        runCurrent()
        assertTrue(events.isEmpty(), "nothing at the gate: $events")

        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()
        assertIs<LiveActivityEvent.Start>(events.first(), "$events")
        assertTrue(events.none { it is LiveActivityEvent.End }, "$events")

        run.session.tap(UserAction.Stop); runCurrent()
        assertEquals(1, events.count { it is LiveActivityEvent.End }, "the cool-down ends it: $events")
        run.session.tap(UserAction.SkipCooldown); runCurrent()
        val closing = async { run.close() }
        advanceTimeBy(1.seconds); runCurrent()
        closing.await()
        assertEquals(1, events.count { it is LiveActivityEvent.End }, "close() must not end it a second time: $events")
    }

    @Test
    fun closing_at_the_gate_never_touches_the_live_activity() = runTest {
        val events = ArrayList<LiveActivityEvent>()
        val run = ActiveRun.open(liveDeps(Trace(), liveActivity = { events += it }), backgroundScope)
        runCurrent()
        val closing = async { run.close() }
        advanceTimeBy(1.seconds); runCurrent()
        closing.await()
        assertTrue(events.isEmpty(), "$events")
    }

    @Test
    fun permissions_then_gps_collected_then_let_go_on_close() = runTest {
        val trace = Trace()
        val log = ArrayList<String>()
        val run = ActiveRun.open(liveDeps(trace, log = { log += it }), backgroundScope)
        runCurrent()
        assertEquals(listOf("permissions", "collect"), trace.events, "the prompt comes before GPS (and so the keep-alive), GPS once the session runs")
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
        assertEquals(listOf("permissions", "collect", "cancel"), trace.events, "let go exactly once, after the run's last write")
        assertTrue(run.runJob.isCompleted)
    }

    @Test
    fun closing_at_the_gate_discards_and_still_lets_gps_go() = runTest {
        val trace = Trace()
        val run = ActiveRun.open(liveDeps(trace), backgroundScope)
        runCurrent()
        val closing = async { run.close() }
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(EndResult.Discarded, closing.await())
        assertEquals(listOf("permissions", "collect", "cancel"), trace.events)
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
        assertFalse("cancel" in trace.events)
    }
}
