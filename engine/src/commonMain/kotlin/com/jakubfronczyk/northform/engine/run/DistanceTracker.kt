package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.metrics.Geo
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * GPS distance on its own (`Engine/DistanceTracker.swift`, D107/D111/D113/D118): fixes in, counted
 * segments out (at most one per fix). Pure and deterministic, so a rebuilt run counts exactly what
 * the live one did.
 *
 * Swift `mutating func` → Kotlin functions on an immutable data class returning the next tracker
 * together with the result ([Step]). The body is written as a short mutable [Draft] so it reads
 * line-for-line like the Swift, then frozen.
 */
data class DistanceTracker(
    /** Where the next counted distance starts: the last counted smoothed position, or where the phone stood. */
    val anchor: LocationFix? = null,
    /** Good fixes (≤ 20 m) of the last [smoothingWindow]. */
    val smoothing: List<LocationFix> = emptyList(),
    /** Smoothed positions of the last [movingWindow] plus the one before it. */
    val smoothed: List<LocationFix> = emptyList(),
    val moving: Boolean = false,
    /** A GPS gap opened: the next smoothed position goes through the bridging rules (D34). */
    private val bridging: Boolean = false,
    /** The anchor moved without counting: the next counted segment starts a new route segment. */
    private val routeBreak: Boolean = true,
    /** The previous fix of any accuracy, and the last good one: a GPS gap is judged against both (D113, D118). */
    private val previous: LocationFix? = null,
    private val lastGood: LocationFix? = null,
) {
    /** `DistanceTracker.swift:29` — a piece of counted distance, and how the route line draws it. */
    data class CountedSegment(
        val from: LocationFix,
        val to: LocationFix,
        val metres: Double,
        /** The route starts a new line at `from` (after start, a pause or an unbridged gap). */
        val startsRouteSegment: Boolean,
        /** `from` is not where the last segment ended (a back-fill), so the line goes through it. */
        val drawsFrom: Boolean,
    ) {
        /** `DistanceTracker.swift:41` — a back-fill starts its own line at `from`: the stretch before it wasn't counted. */
        val routePoints: List<RoutePoint>
            get() {
                val fresh = startsRouteSegment || drawsFrom
                val start = if (fresh) listOf(RoutePoint(from, startsSegment = true)) else emptyList()
                return start + RoutePoint(to, startsSegment = false)
            }
    }

    data class Step(val tracker: DistanceTracker, val segment: CountedSegment?)

    /**
     * `DistanceTracker.swift:73` — every fix of the run, in order. `counting` is false at the start
     * and while paused: the anchor then follows without counting (D33). A GPS gap is decided here,
     * from the fixes alone (D113): a fix worse than `lostAccuracy`, or `lostAfter` or more after the
     * previous fix or the last good one (D118), unless that fix was stationary.
     */
    fun add(fix: LocationFix, counting: Boolean, lostAfter: Duration, lostAccuracy: Double): Step {
        val d = Draft(this)
        val segment = d.add(fix, counting, lostAfter, lostAccuracy)
        d.previous = fix // Swift `defer { previous = fix }`
        return Step(d.freeze(), segment)
    }

    /** `DistanceTracker.swift:130` — pause or stop while moving: count the lag to the latest good fix. */
    fun flushLag(): Step {
        val anchor = anchor
        val latest = smoothing.lastOrNull()
        if (!moving || anchor == null || latest == null || latest.t < anchor.t) return Step(this, null)
        val d = Draft(this)
        val segment = d.count(anchor, latest)
        return Step(d.freeze(), segment)
    }

    /** `DistanceTracker.swift:137` — restart the smoothing at resume, so paused walking is never counted. */
    fun resume(): DistanceTracker = copy(smoothing = emptyList(), smoothed = emptyList())

    private class Draft(t: DistanceTracker) {
        var anchor = t.anchor
        var smoothing = t.smoothing
        var smoothed = t.smoothed
        var moving = t.moving
        var bridging = t.bridging
        var routeBreak = t.routeBreak
        var previous = t.previous
        var lastGood = t.lastGood

        fun freeze() = DistanceTracker(anchor, smoothing, smoothed, moving, bridging, routeBreak, previous, lastGood)

        fun add(fix: LocationFix, counting: Boolean, lostAfter: Duration, lostAccuracy: Double): CountedSegment? {
            if (fix.horizontalAccuracy > lostAccuracy) {
                gpsGap()
                return null
            }
            // Silence after a stationary fix is not a gap: iOS pauses updates while still (D102).
            if (silence(previous, fix, lostAfter) || silence(lastGood, fix, lostAfter)) gpsGap()
            if (fix.horizontalAccuracy > distanceAccuracy) return null
            lastGood = fix
            val point = smooth(fix)
            val anchor = anchor
            if (anchor == null || !counting) {
                follow(point)
                routeBreak = true
                return null
            }

            if (bridging) {
                // First position after a GPS gap: counted only if the gap is short and plausible (D34).
                bridging = false
                val gap = Geo.distance(anchor, point)
                val seconds = (point.t - anchor.t).inSecondsDouble
                if (!(gap <= bridgeMaxDistance && seconds > 0 && gap / seconds <= bridgeMaxSpeed)) {
                    follow(point) // route gap: not counted, drawn as a break
                    routeBreak = true
                    return null
                }
                return count(anchor, point)
            }

            // Stationary hint: forces "not moving"; the anchor follows so movement afterwards starts here.
            if (fix.stationary == true) {
                follow(point)
                return null
            }

            // Moving gate, the primary rule (D107): how far the smoothed position got over the last movingWindow.
            val wasMoving = moving
            val reference = smoothed.lastOrNull { point.t - it.t >= movingWindow }
            moving = reference?.let { Geo.distance(it, point) > movingMinDistance } ?: false
            if (!moving) return null // the anchor stays at the last counted point
            // On the switch to moving: back-fill the window that proved it, never from before the anchor.
            val from = if (!wasMoving && reference != null && reference.t > anchor.t) reference else anchor
            return count(from, point)
        }

        /** `DistanceTracker.swift:143` — old positions say nothing about where the phone is when GPS is back. */
        private fun gpsGap() {
            smoothing = emptyList()
            smoothed = emptyList()
            moving = false
            bridging = true
        }

        /** `DistanceTracker.swift:153` — `fix` comes `lostAfter` or more after `reference`, which wasn't stationary. */
        private fun silence(reference: LocationFix?, fix: LocationFix, lostAfter: Duration): Boolean {
            if (reference == null || reference.stationary == true) return false
            return fix.t - reference.t >= lostAfter
        }

        /** `DistanceTracker.swift:159` — adds the fix to the buffer and the smoothed position to the history. */
        private fun smooth(fix: LocationFix): LocationFix {
            smoothing = smoothing.filterNot { fix.t - it.t > smoothingWindow } + fix
            var weights = 0.0
            var latitude = 0.0
            var longitude = 0.0
            var accuracy = 0.0
            for (f in smoothing) {
                val w = 1 / max(f.horizontalAccuracy, accuracyFloor).pow(2)
                weights += w
                latitude += w * f.latitude
                longitude += w * f.longitude
                accuracy += w * f.horizontalAccuracy
            }
            val point = LocationFix(
                t = fix.t, latitude = latitude / weights, longitude = longitude / weights,
                horizontalAccuracy = accuracy / weights, stationary = fix.stationary,
            )
            // Keep the moving window plus the one point before it: the moving test reads that one.
            val before = smoothed.indexOfLast { fix.t - it.t >= movingWindow }
            if (before >= 0) smoothed = smoothed.drop(before)
            smoothed = smoothed + point
            return point
        }

        /** `DistanceTracker.swift:183` — the anchor moves without counting: start, pause, stationary, unbridged gap. */
        private fun follow(point: LocationFix) {
            anchor = point
            moving = false
            bridging = false
        }

        /** `DistanceTracker.swift:189`. */
        fun count(from: LocationFix, to: LocationFix): CountedSegment {
            val segment = CountedSegment(
                from = from, to = to, metres = Geo.distance(from, to),
                startsRouteSegment = routeBreak, drawsFrom = from != anchor,
            )
            routeBreak = false
            anchor = to
            return segment
        }
    }

    companion object {
        /** A fix must be at least this accurate to count for distance (D102). */
        const val distanceAccuracy = 20.0
        /** After a GPS gap, the next point is bridged only if the gap is short and plausible (D34). */
        const val bridgeMaxDistance = 200.0
        const val bridgeMaxSpeed = 25 / 3.6
        /** D107 starting values: each fix weighted 1/max(accuracy, floor)²; moving = > 6 m over 10 s (0.6 m/s). */
        val smoothingWindow: Duration = 5.seconds
        val movingWindow: Duration = 10.seconds
        const val movingMinDistance = 6.0
        const val accuracyFloor = 3.0
    }
}
