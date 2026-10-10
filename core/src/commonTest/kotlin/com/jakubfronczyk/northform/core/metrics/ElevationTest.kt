package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ElevationTest {
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun fix(s: Int, altitude: Double?, accuracy: Double = 5.0) =
        LocationFix(t = t0 + s.seconds, latitude = 52.5, longitude = 13.4, horizontalAccuracy = accuracy, altitude = altitude)

    private fun totals(vararg altitudes: Double) = Elevation.compute(altitudes.mapIndexed { i, a -> fix(i, a) })

    /** D25: 2 m hysteresis at its edges, one case per rule. */
    @Test
    fun hysteresis_table() {
        val cases = listOf(
            Triple("flat", totals(100.0, 100.0, 100.0), Elevation.Totals(0.0, 0.0)),
            Triple("noise inside the band never adds up", totals(100.0, 101.0, 100.0, 101.9, 100.0, 101.0), Elevation.Totals(0.0, 0.0)),
            Triple("1.99 m up is noise", totals(100.0, 101.99), Elevation.Totals(0.0, 0.0)),
            Triple("2.0 m up counts, the whole move", totals(100.0, 102.0), Elevation.Totals(2.0, 0.0)),
            Triple("2.0 m down counts", totals(100.0, 98.0), Elevation.Totals(0.0, 2.0)),
            Triple("a jump is credited in full and moves the reference", totals(100.0, 110.5, 110.0, 108.5, 108.4), Elevation.Totals(10.5, 2.0)),
            Triple("up then down", totals(100.0, 105.0, 100.0), Elevation.Totals(5.0, 5.0)),
        )
        for ((name, got, want) in cases) assertEquals(want, got, name)
    }

    /** Steps of 1 m: 101 is inside the band (ref 100), 102 reaches 2 m and credits 2 (ref 102), 103 is 1 m of noise again. */
    @Test
    fun small_steps_are_credited_when_they_sum_past_the_band() {
        assertEquals(Elevation.Totals(2.0, 0.0), totals(100.0, 101.0, 102.0, 103.0))
        assertEquals(Elevation.Totals(4.0, 0.0), totals(100.0, 101.0, 102.0, 103.0, 104.0), "and again two steps later")
    }

    @Test
    fun fixes_without_altitude_or_too_inaccurate_are_skipped_and_the_reference_survives_them() {
        val fixes = listOf(fix(0, 100.0), fix(1, null), fix(2, 150.0, accuracy = 25.0), fix(3, 103.0))
        assertEquals(Elevation.Totals(3.0, 0.0), Elevation.compute(fixes))
    }

    /** D33 for climb: altitude read while paused is skipped; the reference waits at the pause. */
    @Test
    fun paused_fixes_are_skipped() {
        val pauses = listOf(Pause(t0 + 1.seconds, t0 + 3.seconds))
        val fixes = listOf(fix(0, 100.0), fix(1, 110.0), fix(2, 120.0), fix(3, 100.0), fix(4, 104.0))
        assertEquals(Elevation.Totals(4.0, 0.0), Elevation.compute(fixes, pauses))
        assertEquals(Elevation.Totals(24.0, 20.0), Elevation.compute(fixes), "the same fixes count without the pause: +10 +10 −20 +4")
    }

    @Test
    fun steps_carry_the_time_of_the_fix_that_confirmed_them() {
        val steps = Elevation.steps(listOf(fix(0, 100.0), fix(5, 101.0), fix(9, 103.0), fix(12, 100.5)))
        assertEquals(listOf(Elevation.Step(t0 + 9.seconds, 3.0), Elevation.Step(t0 + 12.seconds, -2.5)), steps)
    }
}
