package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.testing.fix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `Tests/EngineTests/DistanceTrackerTests.swift` — the tracker on its own (D111). */
class DistanceTrackerTest {

    /** A tracker standing at 0 m at 4 s (the start point, not counted), with the metres it counted. */
    private class Track {
        var tracker = DistanceTracker()
        var metres = 0.0

        init { add(fix(4, north = 0.0), counting = false) }

        fun move(seconds: IntRange, position: (Int) -> Double) {
            for (s in seconds) add(fix(s, north = position(s)))
        }

        fun add(fix: LocationFix, counting: Boolean = true): DistanceTracker.CountedSegment? {
            val step = tracker.add(fix, counting, lostAfter = RunReducer.gpsLostAfter, lostAccuracy = RunReducer.gpsLostAccuracy)
            tracker = step.tracker
            metres += step.segment?.metres ?: 0.0
            return step.segment
        }

        fun flushLag() {
            val step = tracker.flushLag()
            tracker = step.tracker
            metres += step.segment?.metres ?: 0.0
        }
    }

    @Test
    fun back_fill_never_recounts_distance() {
        val track = Track()
        track.move(6..40) { s -> 3.0 * (s - 5) } // 105 m
        track.move(41..56) { s -> 105 + 0.5 * (s - 40) } // slow: 0.5 m/s
        assertFalse(track.tracker.moving, "the gate dropped")
        track.move(57..80) { s -> 113 + 3.0 * (s - 56) } // back to 3 m/s
        track.flushLag()
        assertTrue(abs(track.metres - 185) < 0.5, "${track.metres} m of 185 m walked")
    }

    @Test
    fun back_fill_starts_a_new_route_line() {
        val track = Track()
        track.move(6..40) { s -> 3.0 * (s - 5) } // 105 m, moving
        track.move(41..80) { s -> 105 + 0.5 * (s - 40) } // 40 s slow: the gate drops, anchor freezes
        val backFill = assertNotNull(
            (81..110).mapNotNull { s -> track.add(fix(s, north = 125 + 3.0 * (s - 80))) }.firstOrNull { it.drawsFrom },
            "the gate reopens with a back-fill",
        )
        assertEquals(true, backFill.routePoints.first().startsSegment, "the back-fill lifts the pen at its start")
        assertEquals(2, backFill.routePoints.size, "its own start, then its end")
    }

    @Test
    fun flush_adds_the_smoothing_lag() {
        val track = Track()
        track.move(6..65) { s -> 3.0 * (s - 5) } // 180 m
        assertTrue(track.metres < 175, "the smoothed point trails")
        track.flushLag()
        assertTrue(abs(track.metres - 180) < 0.5)
    }

    @Test
    fun gps_gap_is_bridged_only_when_plausible() {
        for ((gap, bridged) in listOf(150.0 to true, 400.0 to false)) {
            val track = Track()
            track.move(6..25) { s -> 3.0 * (s - 5) } // 60 m
            track.flushLag()
            val segment = track.add(fix(50, north = 60 + gap)) // 25 s after the last fix: a GPS gap
            if (bridged) {
                assertTrue(abs((segment?.metres ?: 0.0) - gap) < 0.5, "the gap counts")
                assertEquals(false, segment?.startsRouteSegment, "the line goes on")
            } else {
                assertNull(segment, "the gap is not counted")
                val later = (51..70).mapNotNull { s -> track.add(fix(s, north = 60 + gap + 3.0 * (s - 50))) }
                assertEquals(true, later.first().startsRouteSegment, "counting restarts on a new route line")
            }
        }
    }

    @Test
    fun silence_after_a_stationary_fix_is_not_a_gap() {
        val track = Track()
        for (s in 6..20) track.add(fix(s, north = 0.0, stationary = true))
        track.add(fix(35, north = 3.0))
        assertEquals(0.0, track.metres)
        assertFalse(track.tracker.moving)
    }

    @Test
    fun long_stretch_of_mediocre_fixes_is_a_gap() {
        for ((jump, until, bridged) in listOf(Triple(300.0, 85, false), Triple(150.0, 74, true))) {
            val track = Track()
            track.move(6..25) { s -> 3.0 * (s - 5) } // 60 m
            val before = track.metres
            for (s in 26..until) track.add(fix(s, north = 60.0, accuracy = 30.0))
            val segment = track.add(fix(until + 1, north = 60 + jump))
            if (bridged) {
                assertEquals(false, segment?.startsRouteSegment, "bridged: the line goes on")
                assertTrue(track.metres - before in 150.0..160.0, "${track.metres - before} m")
            } else {
                assertNull(segment, "the jump is not counted")
                assertEquals(before, track.metres)
            }
        }
    }

    @Test
    fun short_stretch_of_mediocre_fixes_is_not_a_gap() {
        fun run(withMediocre: Boolean): Double {
            val track = Track()
            track.move(6..25) { s -> 3.0 * (s - 5) }
            if (withMediocre) for (s in 26..33) track.add(fix(s, north = 60.0, accuracy = 30.0))
            track.move(34..60) { s -> 3.0 * (s - 5) }
            track.flushLag()
            return track.metres
        }
        assertEquals(run(withMediocre = false), run(withMediocre = true))
    }
}
