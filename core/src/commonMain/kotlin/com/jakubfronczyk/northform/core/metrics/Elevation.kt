package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.LocationFix
import kotlin.time.Instant

/**
 * R7 — elevation gain and loss from GPS altitude with hysteresis (D25: no barometer in v1.0).
 *
 * The reference altitude is the last one that counted; a fix counts only when it has moved at
 * least [hysteresis] away from it, and then the whole move is credited and the reference jumps
 * there. Noise smaller than the band never adds up; a real climb taken in small steps is still
 * credited once the steps sum past the band. Fixes without altitude, fixes worse than the spec 08
 * point filter ([maxAccuracy]) and fixes inside a pause (D33, like distance) are skipped; the
 * reference survives them, so a pause on a hill credits nothing when the run resumes where it stopped.
 */
object Elevation {
    /** D25 ◻: the band GPS altitude must leave before a change counts. */
    const val hysteresis = 2.0

    /** Spec 08 "Point filter": the ONE 20 m a fix needs to count, for altitude here and for distance (`DistanceTracker.distanceAccuracy` reads it). */
    const val maxAccuracy = 20.0

    /** One counted altitude change at the fix that confirmed it: positive up, negative down. */
    data class Step(val at: Instant, val metres: Double)

    data class Totals(val ascent: Double, val descent: Double)

    /** [fixes] in time order. */
    fun steps(fixes: List<LocationFix>, pauses: List<Pause> = emptyList(), hysteresis: Double = Elevation.hysteresis): List<Step> {
        require(hysteresis >= 0) { "hysteresis $hysteresis" }
        val steps = ArrayList<Step>()
        var reference: Double? = null
        for (fix in fixes) {
            val altitude = fix.altitude ?: continue
            if (fix.horizontalAccuracy > maxAccuracy) continue
            if (Pauses.isPaused(pauses, fix.t)) continue
            val ref = reference
            if (ref == null) { reference = altitude; continue }
            val change = altitude - ref
            if (change >= hysteresis || -change >= hysteresis) {
                steps += Step(fix.t, change)
                reference = altitude
            }
        }
        return steps
    }

    fun totals(steps: List<Step>): Totals = Totals(
        ascent = steps.filter { it.metres > 0 }.sumOf { it.metres },
        // Summed as positives: negating an empty sum would give −0.0, which is not `==` 0.0 in a data class.
        descent = steps.filter { it.metres < 0 }.sumOf { -it.metres },
    )

    fun compute(fixes: List<LocationFix>, pauses: List<Pause> = emptyList(), hysteresis: Double = Elevation.hysteresis): Totals =
        totals(steps(fixes, pauses, hysteresis))
}
