package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A heart rate held from one sample until the next: `bpm` applies to `[from, from + duration)`.
 * The sample-and-hold reading of a 1 Hz stream, the same shape an HR graph draws.
 */
data class HrInterval(val from: Instant, val duration: Duration, val bpm: Int) {
    val until: Instant get() = from + duration
}

/**
 * How every summary metric reads the stored heart-rate stream (metrics/01, 02, 06, 07): invalid
 * samples dropped (spec 08, [HeartRate.valid]), then consecutive samples paired into held
 * intervals; a pair further apart than [gap] is an HR gap and contributes nothing ("HR gaps
 * skipped"). The sum of the intervals is the recording's held HR time.
 */
object HrStream {
    /** The reducer's HR-lost rule: no sample for 5 s (`RunReducer.hrLostAfter`), restated here so `:core` stays below `:engine`. */
    val gap: Duration = 5.seconds

    /** [samples] in time order (the store and the fixtures guarantee it). */
    fun intervals(samples: List<HrSample>, gap: Duration = HrStream.gap): List<HrInterval> {
        val valid = samples.filter { HeartRate.isValid(it.bpm) }
        // zipWithNext: consecutive pairs (a, b) → the interval a holds until b arrives.
        return valid.zipWithNext { a, b -> HrInterval(a.t, b.t - a.t, a.bpm) }
            .filter { it.duration > Duration.ZERO && it.duration <= gap }
    }

    /** Σ of the held intervals: the time a heart rate was known. */
    fun heldTime(samples: List<HrSample>, gap: Duration = HrStream.gap): Duration =
        intervals(samples, gap).fold(Duration.ZERO) { acc, it -> acc + it.duration }
}
