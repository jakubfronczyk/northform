package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.ticks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `Tests/EngineTests/HRLostAlertTests.swift` (the hand-written cases; the run-2 replay case is in :replay). D108, D119. */
class HrLostAlertTest {

    private fun runWithHr(until: Int): RunHarness = activeRun().also { beat(it, 6..until) }

    private fun beat(run: RunHarness, seconds: IntRange) {
        for (s in seconds) {
            run.send(RunInput.Hr(HrSample(at(s), 150)))
            run.send(RunInput.Tick(at(s)))
        }
    }

    private fun silence(run: RunHarness, seconds: IntRange) = ticks(run, seconds)

    private fun hrLostAlerts(run: RunHarness) = run.alerts.count { it == Alert.HrLost }
    private fun events(run: RunHarness, kind: EventKind) = run.recordedEvents.count { it.kind == kind }

    @Test
    fun short_blip_is_recorded_but_silent() {
        val run = runWithHr(until = 20)
        silence(run, 21..26)
        assertTrue(run.state.snapshot.hrLost)
        for (i in 0 until 5) run.send(RunInput.Hr(HrSample(at(27 + i * 0.001), 150))) // the burst
        beat(run, 28..40)
        assertEquals(1, events(run, EventKind.HrLost))
        assertEquals(1, events(run, EventKind.HrBack))
        assertEquals(0, hrLostAlerts(run))
    }

    @Test
    fun alert_plays_after_10_seconds_not_at_5() {
        val run = runWithHr(until = 20)
        silence(run, 21..25)
        assertTrue(run.state.snapshot.hrLost, "the gap starts at 5 s")
        assertEquals(0, hrLostAlerts(run))
        silence(run, 26..29)
        assertEquals(0, hrLostAlerts(run))
        silence(run, 30..32)
        assertEquals(1, hrLostAlerts(run), "at 10 s")
    }

    @Test
    fun long_loss_buzzes_once() {
        val run = runWithHr(until = 20)
        silence(run, 21..50)
        assertEquals(1, hrLostAlerts(run))
    }

    @Test
    fun every_loss_buzzes_once() {
        val run = runWithHr(until = 20)
        silence(run, 21..35)
        beat(run, 36..50)
        silence(run, 51..65)
        assertEquals(2, hrLostAlerts(run))
    }

    @Test
    fun only_invalid_heart_rate_is_like_none() {
        val run = activeRun()
        for (s in 6..25) {
            run.send(RunInput.Hr(HrSample(at(s), 25)))
            run.send(RunInput.Tick(at(s)))
        }
        assertTrue(run.state.snapshot.hrLost)
        assertEquals(1, hrLostAlerts(run))
        assertTrue(run.stored.hr.isEmpty())
        assertEquals(null, run.state.snapshot.hrAvg)
    }

    @Test
    fun invalid_sample_does_not_end_an_hr_loss() {
        val run = runWithHr(until = 20)
        silence(run, 21..35) // lost at 25 s, alert at 30 s
        run.send(RunInput.Hr(HrSample(at(36), 250)))
        silence(run, 37..60)
        assertTrue(run.state.snapshot.hrLost)
        assertEquals(0, events(run, EventKind.HrBack))
        assertEquals(1, hrLostAlerts(run))
    }

    @Test
    fun heart_rate_limits() {
        for ((bpm, valid) in listOf(29 to false, 30 to true, 230 to true, 231 to false)) {
            val run = activeRun()
            run.send(RunInput.Hr(HrSample(at(6), bpm)))
            assertEquals(valid, run.state.snapshot.hrNow == bpm, "$bpm bpm")
            assertEquals(valid, run.state.snapshot.hrMax == bpm, "$bpm bpm")
        }
    }
}
