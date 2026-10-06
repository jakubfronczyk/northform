package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.move
import com.jakubfronczyk.northform.testing.ticks
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Tests/EngineTests/GPSDistanceTests.swift` + `DriftTests.swift`, hand-written cases (D107/D102).
 * The pinned real-recording totals live in :replay (`RealRecordingsTest`), where the fixtures are.
 */
class GpsDistanceTest {

    @Test
    fun gps_quality_no_longer_sets_when_distance_counts() {
        val run = activeRun(standingFix = true)
        val marks = ArrayList<Double>()
        for (s in 6..305) {
            run.send(RunInput.Fix(fix(s, north = 1.4 * (s - 5), accuracy = if ((s / 30) % 2 == 0) 4.0 else 15.0)))
            run.send(RunInput.Tick(at(s)))
            if ((s - 5) % 10 == 0) marks += run.state.snapshot.distance
        }
        val perTen = marks.zipWithNext { a, b -> b - a }.drop(2) // the gate needs 10 s first
        for (metres in perTen) assertTrue(abs(metres - 14) <= 14 * 0.15, "$metres m in 10 s, walking 14 m")
    }

    @Test
    fun first_seconds_of_walking_count_once_moving_is_proven() {
        val run = activeRun(standingFix = true)
        move(run, 6..35) { 0.0 } // standing, no stationary hint
        assertEquals(0.0, run.state.snapshot.distance)
        move(run, 36..45) { s -> 1.4 * (s - 35) } // 14 m
        move(run, 46..70) { 14.0 } // standing at 14 m
        assertTrue(abs(run.state.snapshot.distance - 14) < 0.5, "${run.state.snapshot.distance} m")
    }

    @Test
    fun walking_while_paused_does_not_count_after_resume() {
        val run = activeRun(standingFix = true)
        move(run, 6..35) { s -> 3.0 * (s - 5) } // 90 m
        run.send(RunInput.User(UserAction.Pause, at(35.5)))
        move(run, 36..55) { s -> 90.0 + (s - 35) } // 20 m walked while paused
        run.send(RunInput.User(UserAction.Resume, at(55.5)))
        move(run, 56..85) { s -> 110 + 3.0 * (s - 55) } // 90 m
        run.send(RunInput.User(UserAction.Pause, at(85.5)))
        assertTrue(abs(run.state.snapshot.distance - 177) < 0.5, "${run.state.snapshot.distance} m")
    }

    @Test
    fun stationary_hint_forces_not_moving() {
        val run = activeRun(standingFix = true)
        for (s in 6..65) {
            run.send(RunInput.Fix(fix(s, north = (s - 5) * 1.0, stationary = true))) // drifts 1 m/s
            run.send(RunInput.Tick(at(s)))
        }
        assertEquals(0.0, run.state.snapshot.distance)
        assertFalse(run.state.progress.tracker.moving)
    }

    @Test
    fun split_pace_appears_at_50_metres() {
        for ((metres, shown) in listOf(49.0 to false, 51.0 to true)) {
            val run = activeRun()
            run.send(RunInput.Fix(fix(6, north = 0.0)))
            run.send(RunInput.Fix(fix(36, north = metres)))
            run.send(RunInput.Tick(at(36)))
            assertEquals(shown, run.state.snapshot.splitPace != null, "$metres m")
        }
    }

    @Test
    fun current_pace_appears_at_20_metres_in_the_window() {
        for ((metres, shown) in listOf(19.0 to false, 21.0 to true)) {
            val run = activeRun()
            run.send(RunInput.Fix(fix(6, north = 0.0)))
            run.send(RunInput.Fix(fix(16, north = 10.0))) // first counted mark
            run.send(RunInput.Fix(fix(26, north = 10 + metres))) // window covers `metres`
            assertEquals(shown, run.state.snapshot.currentPace != null, "$metres m")
        }
    }

    @Test
    fun movement_after_standing_still_starts_where_the_phone_is() {
        val run = activeRun()
        for (s in 6..25) run.send(RunInput.Fix(fix(s, north = if (s % 2 == 0) 4.0 else -4.0, stationary = true)))
        assertEquals(0.0, run.state.snapshot.distance, "nothing counted while still")
        for (s in 26..45) run.send(RunInput.Fix(fix(s, north = 1.4 * (s - 25)))) // walks 28 m
        for (s in 46..60) run.send(RunInput.Fix(fix(s, north = 28.0))) // stands
        assertTrue(abs(run.state.snapshot.distance - 28) < 1, "${run.state.snapshot.distance} m: from where it stood")
    }

    @Test
    fun silence_after_a_stationary_fix_is_not_gps_loss() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(6, north = 0.0, stationary = true)))
        ticks(run, 7..66)
        assertFalse(run.state.snapshot.gpsLost)
        assertFalse(run.recordedEvents.any { it.kind == EventKind.GpsLost }, "no false GPS-lost event")
    }

    @Test
    fun silence_after_a_moving_fix_is_gps_loss_after_10_seconds() {
        val run = activeRun()
        run.send(RunInput.Fix(fix(6, north = 0.0)))
        ticks(run, 7..15)
        assertFalse(run.state.snapshot.gpsLost)
        run.send(RunInput.Tick(at(16)))
        assertTrue(run.state.snapshot.gpsLost)
        assertTrue(run.recordedEvents.any { it.kind == EventKind.GpsLost })
    }
}
