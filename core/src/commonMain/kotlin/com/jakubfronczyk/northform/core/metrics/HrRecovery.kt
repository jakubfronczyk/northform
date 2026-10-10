package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H7 — heart-rate recovery (spec 08): HR at stop − HR 60 s later, read from the cool-down samples
 * (D29). Run and walk only (D32), which the caller decides. A skipped cool-down means no value:
 * the number is measured or absent, never estimated from a shorter window.
 */
object HrRecovery {
    /** The cool-down the reducer runs after hold-to-stop (`RunReducer.cooldown`), restated so `:core` stays below `:engine`. */
    val cooldown: Duration = 60.seconds

    /**
     * @param samples the recording's heart-rate samples in time order, cool-down included.
     * @param stoppedAt the moment of hold-to-stop (`Recording.endUtc`).
     * @param cooldownEndedAt the `CooldownEnd` event; null while the cool-down runs. The cool-down
     *   counts as complete only when it reached [cooldown] after the stop — the reducer ends a full one
     *   at exactly stop + 60 s, a skip earlier.
     * @param tolerance how far back from each mark the last sample may be (the HR-lost gap).
     * @return bpm at stop − bpm 60 s later, or null when either reading is missing or the cool-down was skipped.
     */
    fun compute(
        samples: List<HrSample>,
        stoppedAt: Instant,
        cooldownEndedAt: Instant?,
        cooldown: Duration = HrRecovery.cooldown,
        tolerance: Duration = HrStream.gap,
    ): Int? {
        if (cooldownEndedAt == null || cooldownEndedAt - stoppedAt < cooldown) return null
        val atStop = reading(samples, stoppedAt, tolerance) ?: return null
        val later = reading(samples, stoppedAt + cooldown, tolerance) ?: return null
        return atStop - later
    }

    /** The last valid sample in `(mark − tolerance, mark]`: the heart rate known at `mark`. */
    private fun reading(samples: List<HrSample>, mark: Instant, tolerance: Duration): Int? =
        samples.lastOrNull { it.t <= mark && it.t > mark - tolerance && HeartRate.isValid(it.bpm) }?.bpm
}
