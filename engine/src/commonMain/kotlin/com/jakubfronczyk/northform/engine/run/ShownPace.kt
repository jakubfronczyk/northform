package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero

/**
 * The current pace as it is SHOWN (D22, Swift D122): rounded to the nearest [step] s/km, Garmin-style.
 * One pure function the run screen and the Live Activity both call, so the two never disagree.
 *
 * Only the display rounds: [RunSnapshot.currentPace], the stored pace and the summary pace keep the
 * D106 rule untouched (30 s window, 15 s hold, 20 m minimum). There is no refresh throttle — the
 * number still updates every second and looks steady because it steps in 5 s (a throttle would add
 * up to 5 s of lag for nothing).
 *
 * Kotlin idiom: a `const val` on an `object` is the Kotlin form of a namespaced constant; `?.let`
 * keeps "hidden" (null) hidden without a branch.
 */
object ShownPace {
    /** Seconds per km between two shown values. */
    const val step = 5.0

    /** `secondsPerKm` to the nearest multiple of [step] (ties away from zero, like `RunFormat.pace`); null stays null. */
    fun round(secondsPerKm: Double?): Double? = secondsPerKm?.let { (it / step).roundedHalfAwayFromZero() * step }
}
