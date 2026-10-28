package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.zone
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * run-flow-ui/05: the whole flow Gate → Done driven through [RunSessionHandle] only, on `runTest`'s
 * virtual clock (no real sleeps), checking at each phase what the screen models would show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunFlowScriptTest {

    private val t0 = Instant.fromEpochSeconds(1_700_000_000)

    /** One virtual time for `delay` and stamps (the `:testSupport` helper, inlined: this module adds no test deps beyond kotlin-test + coroutines-test). */
    private fun TestScope.clock(): WallClock = WallClock { t0 + testScheduler.currentTime.milliseconds }

    /** A tap stamped with the run's own clock, as `RunFlow.send` does (D100). */
    private suspend fun RunSessionHandle.tap(action: UserAction) = send(action, wallClock.now())

    @Test
    fun gate_to_done_through_the_handle() = runTest {
        val clock = clock()
        val fake = ScriptedFakeRunSession(clock)
        val session: RunSessionHandle = fake

        // Gate: nothing seen → Start held; sensors arrive → Start enabled (option A).
        assertEquals(RunPhase.Gate, session.snapshots.value.phase)
        assertFalse(GateModel.from(session.snapshots.value).startEnabled)
        fake.script { copy(hrLost = false, hrNow = 74, gpsLost = false) }
        assertTrue(GateModel.from(session.snapshots.value).startEnabled)

        // Start → a 5 s countdown on the run's clock.
        session.tap(UserAction.Start)
        assertEquals(RunPhase.Countdown(t0 + 5.seconds), session.snapshots.value.phase)
        assertEquals(EndResult.Discarded, session.end(), "closing during the countdown drops the run (D116)")

        // The countdown ends: Active, started at the countdown's end.
        advanceTimeBy(5_000); fake.advanceTo(clock.now())
        assertEquals(RunPhase.Active, session.snapshots.value.phase)
        assertEquals(t0 + 5.seconds, session.snapshots.value.startedAt)

        // One minute in, with a reading: the live model shows it.
        fake.script { copy(hrNow = 152, hrAvg = 148, hrMax = 171, zone = HRZones.standard.zone(3), currentPace = 304.0, splitPace = 312.0, distance = 200.0) }
        advanceTimeBy(60_000); fake.advanceTo(clock.now())
        val live = LiveRunModel.from(session.snapshots.value, clock.now())
        assertEquals("1:00", live.time)
        assertEquals(LiveRunModel.Hr.Reading("152", HRZones.standard.zone(3)), live.hr)
        assertEquals("5:05", live.pace)

        // Pause: the pill counts on the run's clock; Resume returns to Active.
        session.tap(UserAction.Pause)
        assertIs<RunPhase.Paused>(session.snapshots.value.phase)
        advanceTimeBy(42_000); fake.advanceTo(clock.now())
        val paused = LiveRunModel.from(session.snapshots.value, clock.now())
        assertTrue(paused.paused)
        assertEquals(42.seconds, paused.pausedFor)
        assertEquals(NO_VALUE, paused.pace)
        session.tap(UserAction.Resume)
        assertEquals(RunPhase.Active, session.snapshots.value.phase)

        // Hold-to-stop → a 60 s cool-down; closing now finishes rather than discards.
        session.tap(UserAction.Stop)
        assertEquals(RunPhase.Cooldown(clock.now() + 60.seconds), session.snapshots.value.phase)
        assertEquals(EndResult.Finishing, session.end())

        // The cool-down runs out: Done, and the flow closes itself (summary-screen/01: the root opens the Summary).
        assertFalse(RunFlow.closesAt(session.snapshots.value), "open through the cool-down")
        advanceTimeBy(60_000); fake.advanceTo(clock.now())
        assertEquals(RunPhase.Done, session.snapshots.value.phase)
        assertTrue(RunFlow.closesAt(session.snapshots.value))

        assertEquals(listOf(UserAction.Start, UserAction.Pause, UserAction.Resume, UserAction.Stop), fake.sent.map { it.first })
    }

    /**
     * run-flow-artboards: the countdown counts on the run's clock, and its ✕ is the flow's close (`onClose` →
     * `AppModel.closeRun`), which sends nothing: the session ends with nothing recorded and is discarded (D116).
     */
    @Test
    fun cancel_during_the_countdown_closes_the_flow_and_discards_the_run() = runTest {
        val fake = ScriptedFakeRunSession(clock())
        fake.tap(UserAction.Start)
        advanceTimeBy(2_400); fake.advanceTo(fake.wallClock.now())
        val phase = assertIs<RunPhase.Countdown>(fake.snapshots.value.phase)
        val shown = CountdownModel.from(phase.endsAt, fake.wallClock.now(), fake.snapshots.value.type)
        assertEquals("3", shown.number)
        assertEquals(2, shown.litDots)
        assertFalse(RunFlow.closesAt(fake.snapshots.value), "the flow closes on ✕, not by itself")

        // ✕ as the flow wires it: `send` is the flow's tap path to the session, `close` its `onClose` (→ AppModel.closeRun → end()).
        var closed = false
        RunFlow.cancelCountdown(send = { action -> launch { fake.tap(action) } }, close = { closed = true })
        runCurrent()
        assertTrue(closed, "✕ closes the flow")
        assertEquals(listOf<UserAction>(UserAction.Start), fake.sent.map { it.first }, "✕ sends no action: only the gate's Start reached the session")
        assertIs<RunPhase.Countdown>(fake.snapshots.value.phase, "nothing moved the run on")
        assertEquals(EndResult.Discarded, fake.end(), "✕ before the countdown ends records nothing")
    }

    /**
     * run-flow-artboards: Stop opens the Cool-down, whose trace folds `hrNow` per tick from the snapshots the screen
     * sees; Skip (the existing `SkipCooldown`) ends it at once and the flow closes itself.
     */
    @Test
    fun stop_then_cool_down_then_skip_then_done() = runTest {
        val clock = clock()
        val fake = ScriptedFakeRunSession(clock)
        fake.tap(UserAction.Start)
        advanceTimeBy(5_000); fake.advanceTo(clock.now())
        fake.script { copy(hrLost = false, hrNow = 172, distance = 10_210.0) }
        advanceTimeBy(60_000); fake.advanceTo(clock.now())
        fake.tap(UserAction.Stop)
        assertIs<RunPhase.Cooldown>(fake.snapshots.value.phase)

        // The screen's fold: one sample per second, the sensor reading falling 1 bpm a second.
        var trace = CooldownTrace()
        for (s in 0..22) {
            if (s > 0) { advanceTimeBy(1_000); fake.advanceTo(clock.now()); fake.script { copy(hrNow = 172 - s) } }
            trace = trace.add(fake.snapshots.value, clock.now())
        }
        val shown = CooldownModel.from(fake.snapshots.value, clock.now(), trace)
        assertEquals("0:38", shown.left)
        assertEquals("1:00 · 10.21 km", shown.totals)
        assertEquals("172", shown.startedAt)
        assertEquals("150", shown.hrNow)
        assertEquals("−22", shown.drop)
        assertFalse(RunFlow.closesAt(fake.snapshots.value), "open through the cool-down")

        fake.tap(UserAction.SkipCooldown)
        assertEquals(RunPhase.Done, fake.snapshots.value.phase)
        assertTrue(RunFlow.closesAt(fake.snapshots.value), "Skip ends the cool-down and the flow closes itself")
        assertEquals(trace, trace.add(fake.snapshots.value, clock.now()), "nothing collected after the cool-down")
        assertEquals(listOf(UserAction.Start, UserAction.Stop, UserAction.SkipCooldown), fake.sent.map { it.first })
    }

    @Test
    fun skip_cooldown_ends_the_run_at_once() = runTest {
        val fake = ScriptedFakeRunSession(clock())
        fake.tap(UserAction.Start)
        advanceTimeBy(5_000); fake.advanceTo(fake.wallClock.now())
        fake.tap(UserAction.Stop)
        fake.tap(UserAction.SkipCooldown)
        assertEquals(RunPhase.Done, fake.snapshots.value.phase)
    }

    @Test
    fun a_tap_in_the_wrong_phase_changes_nothing() = runTest {
        val fake = ScriptedFakeRunSession(clock())
        fake.tap(UserAction.Stop)
        fake.tap(UserAction.Pause)
        fake.tap(UserAction.SkipCooldown)
        assertEquals(RunPhase.Gate, fake.snapshots.value.phase, "only Start leaves the gate")
        fake.tap(UserAction.Start)
        fake.tap(UserAction.Pause)
        assertIs<RunPhase.Countdown>(fake.snapshots.value.phase, "no pause during the countdown")
    }

    @Test
    fun the_countdown_waits_for_its_whole_five_seconds() = runTest {
        val fake = ScriptedFakeRunSession(clock())
        fake.tap(UserAction.Start)
        advanceTimeBy(4_900); fake.advanceTo(fake.wallClock.now())
        assertIs<RunPhase.Countdown>(fake.snapshots.value.phase)
        advanceTimeBy(100); fake.advanceTo(fake.wallClock.now())
        assertEquals(RunPhase.Active, fake.snapshots.value.phase)
    }
}
