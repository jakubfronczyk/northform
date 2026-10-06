package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.t0
import com.jakubfronczyk.northform.testing.walk
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `Tests/EngineTests/CurrentPaceTests.swift` (hand-written cases; the run-1 replay cases are in :replay). D106. */
class CurrentPaceTest {

    @Test
    fun pause_does_not_drag_current_pace_slow_after_resume() {
        val run = activeRun()
        val speed = 1000.0 / 300 // 5:00 /km
        var north = walk(run, from = 6, to = 125, speed = speed, startNorth = 0.0, ticks = true)
        val before = assertNotNull(run.state.snapshot.currentPace)
        run.send(RunInput.User(UserAction.Pause, at(125.5)))
        for (s in 126..145) { // standing, paused, 20 s
            run.send(RunInput.Fix(fix(s, north = north)))
            run.send(RunInput.Tick(at(s)))
        }
        run.send(RunInput.User(UserAction.Resume, at(145.5)))
        north = walk(run, from = 146, to = 165, speed = speed, startNorth = north, ticks = true)
        val after = assertNotNull(run.state.snapshot.currentPace, "shown 20 s after resume")
        assertTrue(after - before < 5, "before $before, after $after s/km")
    }

    @Test
    fun current_pace_hides_after_15_seconds_without_distance() {
        for ((standing, shown) in listOf(14.0 to true, 16.0 to false)) {
            val run = activeRun()
            val north = walk(run, from = 6, to = 65, speed = 3.0, startNorth = 0.0, ticks = true)
            assertNotNull(run.state.snapshot.currentPace)
            val lastMark = (assertNotNull(run.state.progress.currentPace.lastMark) - t0).inSecondsDouble
            var s = 66.0
            while (s <= lastMark + standing) {
                run.send(RunInput.Fix(fix(s, north = north, stationary = true)))
                run.send(RunInput.Tick(at(s)))
                s += 1
            }
            assertEquals(shown, run.state.snapshot.currentPace != null, "standing $standing s")
        }
    }

    /** Driven through `CurrentPace` directly: the pace rule (D106), whatever the distance rule produces. */
    @Test
    fun one_distance_chunk_moves_current_pace_only_as_much_as_the_speed_changed() {
        var pace = CurrentPace()
        var t = at(6)
        var total = 0.0
        pace = pace.append(total, t)
        for (i in 0 until 24) {
            t += 5.seconds
            total += if (i % 2 == 0) 15.0 else 1000.0 / 30 - 15
            pace = pace.append(total, t)
        }
        val before = assertNotNull(pace.pace(t, active = true))
        assertEquals(before, pace.pace(t + 2.seconds, active = true), "holds between chunks")
        pace = pace.append(total + 12, t + 3.6.seconds)
        val after = assertNotNull(pace.pace(t + 3.6.seconds, active = true))
        assertTrue(abs(after - before) < 5, "before $before, after $after s/km")
    }
}
