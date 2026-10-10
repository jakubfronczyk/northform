package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * E12 / C6 — an optical sensor can freeze at its last value while the wearer moves. A heart rate
 * flat for more than 20 s while GPS says the body is moving (> 0.5 m/s) is marked low confidence,
 * so the summary graph can grey that stretch out. The samples stay; only the trust changes.
 */
object HrConfidence {
    /** A span of samples the summary should not trust, first to last sample of the flat stretch. */
    data class Stretch(val from: Instant, val until: Instant) {
        val duration: Duration get() = until - from
        operator fun contains(t: Instant): Boolean = t >= from && t <= until
    }

    val flatFor: Duration = 20.seconds

    /** Spec 08 "Moving time": below 0.5 m/s is standing (walk 0.3 m/s is a moving-time rule, not this one). */
    const val movingSpeed = 0.5

    /**
     * @param samples heart-rate samples in time order.
     * @param fixes GPS fixes in time order; only those with a `speed` are evidence of motion.
     * @return the flat stretches longer than [flatFor] during which the fixes' mean speed exceeded
     *   [movingSpeed]. A stretch without any speed-carrying fix is never flagged: no evidence of
     *   motion is not evidence of a freeze (standing still, or GPS lost).
     */
    fun lowConfidence(
        samples: List<HrSample>,
        fixes: List<LocationFix>,
        flatFor: Duration = HrConfidence.flatFor,
        movingSpeed: Double = HrConfidence.movingSpeed,
        gap: Duration = HrStream.gap,
    ): List<Stretch> = flatStretches(samples, flatFor, gap).filter { stretch ->
        val speeds = fixes.filter { it.t in stretch }.mapNotNull { it.speed }
        speeds.isNotEmpty() && speeds.average() > movingSpeed
    }

    /** Runs of the same valid bpm, unbroken by a gap longer than [gap], spanning more than [flatFor]. */
    fun flatStretches(samples: List<HrSample>, flatFor: Duration = HrConfidence.flatFor, gap: Duration = HrStream.gap): List<Stretch> {
        val out = ArrayList<Stretch>()
        var start: HrSample? = null
        var previous: HrSample? = null
        fun close() {
            val s = start ?: return
            val p = previous ?: return
            if (p.t - s.t > flatFor) out += Stretch(s.t, p.t)
        }
        for (sample in samples.filter { HeartRate.isValid(it.bpm) }) {
            val p = previous
            val continues = p != null && sample.bpm == p.bpm && sample.t - p.t <= gap
            if (!continues) {
                close()
                start = sample
            }
            previous = sample
        }
        close()
        return out
    }
}
