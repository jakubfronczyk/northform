package com.jakubfronczyk.northform.adapters.clock

import com.jakubfronczyk.northform.core.ports.WallClock
import kotlin.time.Clock

/** The one real clock. `kotlin.time.Clock.System` is Stable since 2.3.0 (https://kotlinlang.org/docs/whatsnew23.html). */
object IosWallClock : WallClock {
    override fun now() = Clock.System.now()
}
