package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.inSecondsDouble
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How fast you run now (`Engine/CurrentPace.swift`, D106), on its own: the reducer appends distance
 * as it is counted and clears it at pause and resume; the snapshot asks for the pace. Pure.
 *
 * The window is [window] of counted distance ending at the latest mark, so it only moves when
 * distance is counted; its start is read off the distance line, linear between marks. Hidden while
 * paused, after [hold] without counted distance, or while the window covers less than [minDistance].
 */
data class CurrentPace(
    /** (time, total distance) of recent counted distance: the window plus the one mark before it. */
    val marks: List<DistanceMark> = emptyList(),
) {
    /** When distance was last counted. */
    val lastMark: Instant? get() = marks.lastOrNull()?.t

    /** `CurrentPace.swift:23` — the run's total distance just went up to `total` at `t`. */
    fun append(total: Double, at: Instant): CurrentPace {
        var next = marks + DistanceMark(at, total)
        // Keep the window plus the one mark before it: the window start is read off that pair.
        val start = at - window
        val before = next.indexOfLast { it.t <= start }
        if (before >= 0) next = next.drop(before)
        return CurrentPace(next)
    }

    /** `CurrentPace.swift:33` — pause and resume: paused time never enters the window. */
    fun clear(): CurrentPace = CurrentPace()

    /** `CurrentPace.swift:39` — seconds per km at `now` (the last tick), or null when hidden. */
    fun pace(now: Instant?, active: Boolean): Double? {
        if (!active) return null
        val last = marks.lastOrNull() ?: return null
        if (now != null && now - last.t >= hold) return null
        val span = window() ?: return null
        if (span.metres < minDistance || span.seconds <= 0) return null
        return span.seconds / span.metres * 1000
    }

    private class Span(val seconds: Double, val metres: Double)

    /** `CurrentPace.swift:48` — the window ending at the latest mark; shorter while marks don't reach back that far. */
    private fun window(): Span? {
        val first = marks.firstOrNull() ?: return null
        val last = marks.last()
        val start = last.t - window
        val after = marks.indexOfFirst { it.t > start }
        if (!(first.t < start) || after <= 0) {
            return Span((last.t - first.t).inSecondsDouble, last.distance - first.distance)
        }
        val a = marks[after - 1]
        val b = marks[after]
        val fraction = (start - a.t).inSecondsDouble / (b.t - a.t).inSecondsDouble
        val distanceAtStart = a.distance + (b.distance - a.distance) * fraction
        return Span(window.inSecondsDouble, last.distance - distanceAtStart)
    }

    companion object {
        val window: Duration = 30.seconds
        val hold: Duration = 15.seconds
        const val minDistance = 20.0
    }
}

data class DistanceMark(val t: Instant, val distance: Double)
