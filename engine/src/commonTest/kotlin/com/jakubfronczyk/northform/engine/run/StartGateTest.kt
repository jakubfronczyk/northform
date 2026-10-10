package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R24 / D20 (run 1 started in a stairwell): before the run starts the reducer reports whether HR and GPS
 * are good enough to start. HR ✓ = a sample in the last 5 s · GPS ✓ = 3 consecutive fixes ≤ 20 m. The
 * gate screen reads it off the snapshot: at `Gate`/`Countdown`, `hrLost == false` is HR ✓, `gpsLost ==
 * false` is GPS ✓, `hrNow` is the live bpm. Readiness is derived state — reduced from `Hr`/`Fix`, aged by
 * the 1 Hz tick, no timer.
 */
class StartGateTest {

    private fun gate() = RunHarness(RunHarness.config)
    private fun hr(s: Int, bpm: Int) = RunInput.Hr(HrSample(at(s), bpm))
    private fun tick(s: Int) = RunInput.Tick(at(s))

    @Test
    fun indoors_nothing_is_ready() {
        val run = gate()
        run.send(tick(0))
        assertEquals(RunPhase.Gate, run.state.snapshot.phase)
        assertTrue(run.state.snapshot.hrLost, "no sample yet")
        assertTrue(run.state.snapshot.gpsLost, "no fix yet")
        assertNull(run.state.snapshot.hrNow)
        for (s in 1..5) run.send(RunInput.Fix(fix(s, north = 0.0, accuracy = 35.0))) // the stairwell
        assertTrue(run.state.snapshot.gpsLost, "poor fixes never make GPS ready")
    }

    @Test
    fun three_consecutive_good_fixes_make_gps_ready_and_a_poor_one_starts_over() {
        val run = gate()
        run.send(RunInput.Fix(fix(1, north = 0.0)))
        run.send(RunInput.Fix(fix(2, north = 0.0)))
        assertTrue(run.state.snapshot.gpsLost, "two are not enough")
        run.send(RunInput.Fix(fix(3, north = 0.0)))
        assertFalse(run.state.snapshot.gpsLost, "outdoors: three in a row")
        run.send(RunInput.Fix(fix(4, north = 0.0, accuracy = 20.5)))
        assertTrue(run.state.snapshot.gpsLost, "one poor fix breaks the streak")
        run.send(RunInput.Fix(fix(5, north = 0.0, accuracy = 20.0)))
        run.send(RunInput.Fix(fix(6, north = 0.0)))
        assertTrue(run.state.snapshot.gpsLost)
        run.send(RunInput.Fix(fix(7, north = 0.0)))
        assertFalse(run.state.snapshot.gpsLost, "20 m counts as good; three again")
    }

    @Test
    fun a_sample_makes_hr_ready_until_five_seconds_of_silence() {
        val run = gate()
        run.send(hr(1, 72))
        assertFalse(run.state.snapshot.hrLost)
        assertEquals(72, run.state.snapshot.hrNow, "the gate shows the live bpm")
        for (s in 2..5) run.send(tick(s))
        assertFalse(run.state.snapshot.hrLost, "4 s old: still fresh")
        run.send(tick(6))
        assertTrue(run.state.snapshot.hrLost, "5 s without a sample")
        assertNull(run.state.snapshot.hrNow, "never a stale number")
        run.send(hr(7, 74))
        assertFalse(run.state.snapshot.hrLost)
        assertEquals(74, run.state.snapshot.hrNow)
    }

    @Test
    fun an_invalid_sample_does_not_count_at_the_gate_either() {
        val run = gate()
        run.send(hr(1, 20)) // below 30 bpm (D119)
        assertTrue(run.state.snapshot.hrLost)
        assertNull(run.state.snapshot.hrNow)
    }

    @Test
    fun what_the_gate_sees_is_not_recorded() {
        val run = gate()
        run.send(hr(1, 72))
        for (s in 1..3) run.send(RunInput.Fix(fix(s, north = 0.0)))
        assertFalse(run.state.snapshot.hrLost)
        assertFalse(run.state.snapshot.gpsLost)
        run.send(RunInput.User(UserAction.Start, at(3)))
        run.send(hr(5, 80)) // the countdown: still before start
        for (s in 4..8) run.send(tick(s))
        assertEquals(RunPhase.Active, run.state.snapshot.phase)
        assertNull(run.state.snapshot.hrAvg, "gate and countdown samples never enter the averages")
        assertNull(run.state.snapshot.hrNow, "the run's own HR starts empty; HR lost counts from start (5 s)")
        assertTrue(run.stored.hr.isEmpty() && run.state.buffer.hr.isEmpty(), "nothing from before the start is stored")
        assertTrue(run.stored.fixes.isEmpty() && run.state.buffer.fixes.isEmpty(), "gate fixes are not the starting point (the countdown's are)")
        assertFalse(run.state.snapshot.hrLost, "after start the active rule applies: lost only after 5 s")
        assertFalse(run.state.snapshot.gpsLost, "after start the active rule applies: lost after 10 s or a fix > 50 m")
    }

    @Test
    fun readiness_keeps_updating_through_the_countdown() {
        val run = gate()
        run.send(RunInput.User(UserAction.Start, at(0)))
        assertTrue(run.state.snapshot.phase is RunPhase.Countdown)
        for (s in 1..3) run.send(RunInput.Fix(fix(s, north = 0.0)))
        run.send(hr(3, 70))
        assertFalse(run.state.snapshot.gpsLost)
        assertFalse(run.state.snapshot.hrLost)
    }

    /** D20's other half: "Start anyway" is always allowed — today every Start opens the countdown (see the ticket's decision). */
    @Test
    fun start_opens_the_countdown_whether_ready_or_not() {
        val run = gate()
        run.send(tick(0))
        assertTrue(run.state.snapshot.hrLost && run.state.snapshot.gpsLost, "not ready")
        run.send(RunInput.User(UserAction.Start, at(0)))
        assertEquals(RunPhase.Countdown(endsAt = at(5)), run.state.snapshot.phase)
    }
}
