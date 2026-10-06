package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.metrics.Geo
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.move
import com.jakubfronczyk.northform.testing.t0
import com.jakubfronczyk.northform.testing.walk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `Tests/EngineTests/RunReducerTests.swift`, test by test. */
class RunReducerTest {

    private fun user(a: UserAction, s: Double) = RunInput.User(a, at(s))
    private fun tick(s: Double) = RunInput.Tick(at(s))
    private fun hr(s: Double, bpm: Int) = RunInput.Hr(HrSample(at(s), bpm))
    private fun List<RunEffect>.persistSeq() = filterIsInstance<RunEffect.Persist>().firstOrNull()?.seq
    private fun List<RunEffect>.batch() = filterIsInstance<RunEffect.Persist>().firstOrNull()?.batch

    @Test
    fun countdown_creates_the_recording_at_its_end() {
        val run = activeRun()
        assertEquals(RunPhase.Active, run.state.snapshot.phase)
        assertEquals(at(5), run.stored.recording.startUtc)
        assertEquals(List(5) { Alert.CountdownTick }, run.alerts)
    }

    @Test
    fun last_good_fix_of_the_countdown_is_the_starting_point() {
        val run = RunHarness(RunHarness.config)
        run.send(user(UserAction.Start, 0.0))
        run.send(RunInput.Fix(fix(4, north = 0.0)))
        run.send(RunInput.Fix(fix(5, north = 0.0)))
        run.send(tick(5.0))
        walk(run, from = 6, to = 25, startNorth = 0.0) // 60 m
        run.send(user(UserAction.Pause, 25.0)) // flushes the smoothing lag (D107)
        assertTrue(abs(run.state.snapshot.distance - 60) < 0.5, "counted from the countdown's last fix")
        assertEquals(at(5), run.stored.fixes.first().t, "stored, so a rebuild starts from it too")
    }

    @Test
    fun samples_during_countdown_are_ignored() {
        val run = RunHarness(RunHarness.config)
        run.send(user(UserAction.Start, 0.0))
        run.send(hr(1.0, 100))
        assertNull(run.state.snapshot.hrAvg)
    }

    @Test
    fun hr_lost_counts_from_start_when_no_sample_ever_arrived() {
        val run = activeRun()
        for (s in 6..9) run.send(tick(s.toDouble()))
        assertFalse(run.state.snapshot.hrLost)
        run.send(tick(10.0))
        assertTrue(run.state.snapshot.hrLost)
        assertTrue(run.alerts.lastOrNull() != Alert.HrLost, "the gap starts at 5 s, the alert waits (D108)")
        for (s in 11..15) run.send(tick(s.toDouble()))
        assertEquals(Alert.HrLost, run.alerts.last())
    }

    @Test
    fun gps_lost_after_ten_seconds_without_a_fix() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(5, north = 0.0)))
        run.send(tick(14.0))
        assertFalse(run.state.snapshot.gpsLost)
        run.send(tick(15.0))
        assertTrue(run.state.snapshot.gpsLost)
    }

    @Test
    fun gps_lost_on_poor_accuracy_and_back_on_a_good_fix() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(6, north = 0.0)))
        run.send(RunInput.Fix(fix(7, north = 3.0, accuracy = 60.0)))
        assertTrue(run.state.snapshot.gpsLost)
        run.send(RunInput.Fix(fix(8, north = 6.0)))
        assertFalse(run.state.snapshot.gpsLost)
    }

    @Test
    fun gps_back_150_metres_on_is_bridged() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(5, north = 0.0)))
        walk(run, from = 6, to = 25, startNorth = 0.0) // 60 m
        run.send(tick(36.0)) // lost (10 s without a fix)
        assertTrue(run.state.snapshot.gpsLost)
        run.send(RunInput.Fix(fix(50, north = 210.0))) // 150 m on in 25 s: 21.6 km/h
        assertFalse(run.state.snapshot.gpsLost)
        assertTrue(abs(run.state.snapshot.distance - 210) < 0.5, "the gap counts")
        assertEquals(1, run.route.segments.size)
    }

    @Test
    fun gps_back_400_metres_on_breaks_the_route_and_does_not_count() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(5, north = 0.0)))
        walk(run, from = 6, to = 25, startNorth = 0.0) // 60 m
        run.send(tick(36.0))
        val beforeGap = run.state.snapshot.distance
        run.send(RunInput.Fix(fix(50, north = 460.0))) // 400 m on: over 200 m
        assertEquals(beforeGap, run.state.snapshot.distance, "the gap is not counted")
        walk(run, from = 51, to = 70, startNorth = 460.0) // 60 m more
        run.send(user(UserAction.Pause, 70.0))
        assertTrue(abs(run.state.snapshot.distance - beforeGap - 60) < 0.5, "counting restarts at the new position")
        assertEquals(2, run.route.segments.size)
    }

    @Test
    fun inaccurate_fix_does_not_count_for_distance() {
        fun distance(fifteen: LocationFix?): Double {
            val run = activeRun()
            run.send(RunInput.Fix(fix(5, north = 0.0)))
            for (s in 6..25) {
                if (s == 15) fifteen?.let { run.send(RunInput.Fix(it)) }
                else run.send(RunInput.Fix(fix(s, north = 3.0 * (s - 5))))
            }
            run.send(user(UserAction.Pause, 25.0))
            return run.state.snapshot.distance
        }
        val offTrack = fix(15, north = 30.0, accuracy = 30.0).copy(longitude = 13.4 + 20 / Geo.earthRadius * 180 / PI)
        assertEquals(distance(null), distance(offTrack))
    }

    @Test
    fun late_samples_are_dropped() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(10, north = 0.0)))
        run.send(RunInput.Fix(fix(9, north = 50.0)))
        assertEquals(at(10), run.recordedFixes.last().t)
        run.send(hr(10.0, 120))
        run.send(hr(9.0, 180))
        assertEquals(120, run.state.snapshot.hrMax)
    }

    @Test
    fun total_time_includes_pauses_active_time_does_not() {
        val run = activeRun()
        run.send(user(UserAction.Pause, 15.0))
        run.send(user(UserAction.Resume, 25.0))
        run.send(tick(35.0))
        assertEquals(30.seconds, run.state.snapshot.totalTime)
        assertEquals(20.seconds, run.state.progress.activeTime(at(35)))
    }

    @Test
    fun split_alert_at_every_kilometre() {
        val run = activeRun()
        for (s in 0..400) run.send(RunInput.Fix(fix(6.0 + s, north = s * 3.0)))
        assertEquals(listOf(1), run.recordedEvents.mapNotNull { (it.kind as? EventKind.Split)?.index })
        assertEquals(1, run.alerts.count { it == Alert.Split })
    }

    @Test
    fun no_second_batch_while_one_is_in_flight() {
        var state = RunState(RunHarness.config)
        state = RunReducer.reduce(state, user(UserAction.Start, 0.0)).state
        state = RunReducer.reduce(state, tick(5.0)).state
        val first = RunReducer.reduce(state, tick(10.0)); state = first.state
        val seq = assertNotNull(first.effects.persistSeq())
        val pause = RunReducer.reduce(state, user(UserAction.Pause, 11.0)); state = pause.state
        assertTrue(pause.effects.none { it is RunEffect.Persist }, "waits for the batch in flight")
        val afterAck = RunReducer.reduce(state, RunInput.Persisted(seq))
        assertTrue(afterAck.effects.any { it is RunEffect.Persist }, "the requested flush follows the confirmation")
    }

    @Test
    fun delayed_flush_keeps_the_time_it_was_requested() {
        var state = RunState(RunHarness.config)
        state = RunReducer.reduce(state, user(UserAction.Start, 0.0)).state
        state = RunReducer.reduce(state, tick(5.0)).state
        val first = RunReducer.reduce(state, tick(10.0)); state = first.state
        val seq = assertNotNull(first.effects.persistSeq())
        state = RunReducer.reduce(state, user(UserAction.Pause, 11.5)).state
        val next = RunReducer.reduce(state, RunInput.Persisted(seq))
        val batch = assertNotNull(next.effects.batch())
        assertEquals(at(11.5), batch.persistedAt, "not the older last tick at 10 s")
        assertTrue(batch.events.any { it.kind == EventKind.Pause })
    }

    @Test
    fun going_to_the_background_persists_at_once() {
        var state = RunState(RunHarness.config)
        state = RunReducer.reduce(state, user(UserAction.Start, 0.0)).state
        state = RunReducer.reduce(state, tick(5.0)).state
        state = RunReducer.reduce(state, hr(6.0, 140)).state
        val batch = assertNotNull(RunReducer.reduce(state, RunInput.Backgrounded(at(7))).effects.batch(), "no waiting for the 5 s flush")
        assertEquals(at(7), batch.persistedAt)
        assertEquals(listOf(140), batch.hr.map { it.bpm })
    }

    @Test
    fun snapshot_hides_stale_heart_rate() {
        val run = activeRun()
        run.send(hr(6.0, 150))
        assertEquals(150, run.state.snapshot.hrNow)
        run.send(tick(11.0))
        assertNull(run.state.snapshot.hrNow)
        assertEquals(150, run.state.snapshot.hrMax)
    }

    /** D117: a pause tapped at 10.25 s arriving after a fix stamped 11.0 s is saved with a heartbeat ≥ 11.0 s. */
    @Test
    fun heartbeat_covers_data_newer_than_the_tap() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(6, north = 0.0)))
        run.send(RunInput.Fix(fix(11, north = 15.0)))
        run.send(user(UserAction.Pause, 10.25))
        val stored = run.stored
        assertTrue(stored.events.any { it.kind == EventKind.Pause }, "the pause batch is saved")
        assertEquals(at(11), stored.fixes.last().t)
        assertTrue(assertNotNull(stored.lastPersistedAt) >= at(11))
    }

    /** D121: a segment crossing one km mark part-way times the split at the crossing, not at the fix that ended it. */
    @Test
    fun split_is_timed_where_the_km_mark_was_reached() {
        val run = activeRun(standingFix = true)
        move(run, 6..339) { s -> 3.0 * (s - 5) } // ~994.5 m counted, last fix at 339 s
        for (s in 340..348) run.send(tick(s.toDouble())) // 9 s silent
        val before = run.state.snapshot.distance
        run.send(RunInput.Fix(fix(349, north = before + 33))) // gap bridged: a 33 m segment over 10 s
        val metres = run.state.snapshot.distance - before
        assertTrue(before < 1000 && before + metres > 1000, "the segment crosses 1 km")

        val split = assertNotNull(run.recordedSplits.firstOrNull())
        val offset = (split.t - at(339)).inSecondsDouble
        assertTrue(abs(offset - (1000 - before) / metres * 10) < 0.001, "linear in time along the segment")
        assertTrue(abs(offset - 1.667) < 0.05, "≈ 1/6 of the way in, at $offset s, not at the ending fix (10 s)")
        assertEquals(Alert.Split, run.alerts.last(), "the alert plays when the fix arrives")
    }

    /** D121: each km mark inside one segment keeps its own moment. */
    @Test
    fun each_km_mark_in_one_segment_keeps_its_own_moment() {
        val run = activeRun(standingFix = true)
        move(run, 6..20) { s -> 3.0 * (s - 5) } // ~40 m, moving, anchor at 20 s
        for (s in 21..27) run.send(tick(s.toDouble())) // 7 s silent: no gap (< 10 s), still moving
        val before = run.state.snapshot.distance
        run.send(RunInput.Fix(fix(28, north = 2150.0))) // one segment past both 1 km and 2 km
        val metres = run.state.snapshot.distance - before
        assertTrue(before < 1000 && before + metres > 2000, "one segment crosses two marks")

        val splits = run.recordedSplits
        assertEquals(listOf(EventKind.Split(1), EventKind.Split(2)), splits.map { it.kind })
        for ((mark, split) in listOf(1000.0, 2000.0).zip(splits)) {
            val expected = at(20) + (8.seconds * ((mark - before) / metres))
            assertTrue(abs((split.t - expected).inSecondsDouble) < 0.001, "split at ${(split.t - t0).inSecondsDouble} s")
        }
        assertTrue(splits[0].t < splits[1].t, "each mark at its own moment, not both at the fix")
        assertTrue(splits[1].t < at(28), "both before the fix that ended the segment")
        assertEquals(2, run.alerts.count { it == Alert.Split }, "an alert for each mark, now")
    }
}
