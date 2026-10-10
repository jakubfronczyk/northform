package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * SU6 / E14 — a heart rate above the profile's HRmax held for at least 10 s (◻ spec 08 "Invalid HR")
 * means the HRmax is too low: the summary offers the observed value, and only an accepted suggestion
 * recomputes anything. The samples themselves are kept (they are valid, 30–230); a sample over 230
 * is dropped before this runs and can never become a suggestion.
 */
object HrMaxSuggestion {
    val hold: Duration = 10.seconds

    /**
     * The highest bpm seen in any stretch of consecutive valid samples all above `profile.hrMax`,
     * uninterrupted by a gap longer than [gap] and spanning at least [hold] (first to last sample of
     * the stretch); null when no such stretch exists.
     */
    fun detect(samples: List<HrSample>, profile: Profile, hold: Duration = HrMaxSuggestion.hold, gap: Duration = HrStream.gap): Int? {
        var best: Int? = null
        var stretchStart: HrSample? = null
        var stretchMax = 0
        var previous: HrSample? = null

        fun close(last: HrSample?) {
            val start = stretchStart ?: return
            if (last != null && last.t - start.t >= hold) best = maxOf(best ?: 0, stretchMax)
            stretchStart = null
        }

        for (sample in samples.filter { HeartRate.isValid(it.bpm) }) {
            val broken = previous != null && sample.t - previous.t > gap
            if (broken) close(previous)
            if (sample.bpm > profile.hrMax) {
                if (stretchStart == null) { stretchStart = sample; stretchMax = sample.bpm } else stretchMax = maxOf(stretchMax, sample.bpm)
            } else {
                close(previous)
            }
            previous = sample
        }
        close(previous)
        return best
    }
}
