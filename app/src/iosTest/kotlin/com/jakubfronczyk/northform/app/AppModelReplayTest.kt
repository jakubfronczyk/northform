package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayScript
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The simulator proof for walking-skeleton step 7: the composition root drives a REPLAY run through the
 * whole flow, Gate → Countdown → Active → Cool-down → Done, on a virtual clock, with the phase log the
 * app writes. Same `AppModel` the phone uses; only the ports differ (replay here, live there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelReplayTest {

    /** The test runs inside the simulator sandbox with no bundle: the fixture text is generated into the test sources by Gradle. */
    private fun straightRun(): ReplayScript = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    /** The simulator wiring: a replay source on the test's virtual clock, an in-memory store. */
    private fun TestScope.replayDeps(script: ReplayScript, store: InMemoryRecordingStore, alerts: AlertPlayer) = AppDeps(
        source = RunSource.Replay { script },
        store = store,
        wallClock = virtualWallClock(script.meta.start),
        alerts = alerts,
        newRecordingId = { "00000000-0000-0000-0000-00000000000a" },
    )

    @Test
    fun a_replay_run_goes_from_gate_to_summary() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val alerts = RecordedAlerts()
        val phases = ArrayList<String>()
        val model = AppModel(replayDeps(script, store, alerts), backgroundScope) { line -> phases += line }

        assertNull(model.activeRun.value)
        model.startRun()
        runCurrent()
        val run = assertNotNull(model.activeRun.value, "the run screen opens on the replay session")
        assertEquals(RunPhase.Gate, run.session.snapshot.phase)

        run.session.tap(UserAction.Start) // the Start tap
        advanceTimeBy(6.seconds); runCurrent()
        assertEquals(RunPhase.Active, run.session.snapshot.phase, "countdown over")
        advanceTimeBy(120.seconds); runCurrent()
        val live = run.session.snapshot
        assertTrue(live.distance > 200, "distance climbs on replay GPS: ${live.distance} m")
        assertNotNull(live.hrNow, "live heart rate from the replay sensor")

        run.session.tap(UserAction.Stop) // hold-to-stop
        runCurrent()
        assertTrue(run.session.snapshot.phase is RunPhase.Cooldown)
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(RunPhase.Done, run.session.snapshot.phase, "the Summary screen")

        model.closeRun() // Done tapped
        advanceTimeBy(1.seconds); runCurrent()
        assertNull(model.activeRun.value)
        val stored = assertNotNull(store.records.load(run.id))
        assertEquals(RecordingState.Computing, stored.recording.state)
        assertEquals(5, alerts.played.count { it == Alert.CountdownTick })
        assertTrue(phases.any { "Gate" in it } && phases.any { "Active" in it } && phases.any { "Done" in it }, "lifecycle log: $phases")
    }

    @Test
    fun closing_at_the_gate_discards_and_leaves_no_recording() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val model = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
        model.startRun()
        runCurrent()
        val run = assertNotNull(model.activeRun.value)
        model.closeRun()
        advanceTimeBy(1.seconds); runCurrent()
        assertNull(store.records.load(run.id))
        assertTrue(run.runJob.isCompleted, "run() returned after the discard")
    }
}
