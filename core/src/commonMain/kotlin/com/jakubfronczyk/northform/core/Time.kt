package com.jakubfronczyk.northform.core

import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Swift's `TimeInterval` is a Double of seconds; Kotlin's `Duration` is exact nanoseconds. Where the
 * Swift algorithm divides or multiplies a time by a distance (pace, split interpolation), the
 * Duration is converted here, once, so the arithmetic reads like the original.
 */
val Duration.inSecondsDouble: Double
    get() = toDouble(DurationUnit.SECONDS)

/**
 * Swift's `Double.rounded()` rounds half AWAY from zero; `kotlin.math.round` rounds half to even.
 * Where the port rounds (mean heart rate, fixture offsets), use this so ties match the Swift build.
 */
fun Double.roundedHalfAwayFromZero(): Double = if (this >= 0) floor(this + 0.5) else -floor(-this + 0.5)
