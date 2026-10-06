package com.jakubfronczyk.northform.core.ports

import kotlin.time.Instant

/**
 * The only way the engine learns the wall-clock time (time is data: every input carries its
 * timestamp, the reducer never reads a clock). Tests back it with the virtual scheduler.
 * `kotlin.time.Instant` is Stable since Kotlin 2.3.0: https://kotlinlang.org/docs/whatsnew23.html
 */
fun interface WallClock {
    fun now(): Instant
}
