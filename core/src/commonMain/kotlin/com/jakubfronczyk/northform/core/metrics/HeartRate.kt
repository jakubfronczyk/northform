package com.jakubfronczyk.northform.core.metrics

/**
 * Spec 08 "Invalid HR": a sample under 30 or over 230 bpm is dropped before anything reads it.
 * The ONE definition (metrics/06): the reducer's live drop (D119) and the profile's HRmax ceiling
 * (`Profile.checkHeartRates`) both read it, so the two copies of the Swift build cannot drift apart.
 */
object HeartRate {
    val valid: IntRange = 30..230

    fun isValid(bpm: Int): Boolean = bpm in valid
}
