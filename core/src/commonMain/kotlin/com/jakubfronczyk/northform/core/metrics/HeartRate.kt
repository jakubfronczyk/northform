package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero

/**
 * Spec 08 "Invalid HR": a sample under 30 or over 230 bpm is dropped before anything reads it.
 * The ONE definition (metrics/06): the reducer's live drop (D119) and the profile's HRmax ceiling
 * (`Profile.checkHeartRates`) both read it, so the two copies of the Swift build cannot drift apart.
 */
object HeartRate {
    val valid: IntRange = 30..230

    fun isValid(bpm: Int): Boolean = bpm in valid

    /**
     * H1's average: [sum] / [count] rounded half away from zero; null when [count] is 0. The ONE
     * rounding rule the live snapshot (a running total), the summary and each split row share.
     */
    fun mean(sum: Int, count: Int): Int? =
        if (count == 0) null else (sum.toDouble() / count.toDouble()).roundedHalfAwayFromZero().toInt()

    /** [mean] of [samples] as given (the caller filters for validity). */
    fun mean(samples: List<HrSample>): Int? = mean(samples.sumOf { it.bpm }, samples.size)
}
