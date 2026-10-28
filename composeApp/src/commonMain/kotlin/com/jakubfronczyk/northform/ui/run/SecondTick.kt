package com.jakubfronczyk.northform.ui.run

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How long until the time left to [end] crosses its next whole second, when a rounded-up count (the countdown's
 * number, the cool-down's "0:38") changes: the screens wake then instead of every 100 ms, so their models rebuild once
 * a second. At or past [end] it waits a whole second (the phase is about to change anyway).
 */
internal fun untilNextSecond(end: Instant, now: Instant): Duration {
    val left = end - now
    if (left <= Duration.ZERO) return 1.seconds
    val part = (left.inWholeMilliseconds % 1000).milliseconds
    return if (part == Duration.ZERO) 1.seconds else part
}
