package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.Geo
import com.jakubfronczyk.northform.engine.run.RunInput
import kotlin.math.PI
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// `Tests/EngineTests/TestRun.swift` — one time base, one fix builder, one way to start a run and to
// move it, so every ported test sees the inputs it saw in Swift.

/** The time base of the hand-written runs. */
val t0: Instant = Instant.fromEpochSeconds(1_000_000)

fun at(s: Double): Instant = t0 + s.seconds
fun at(s: Int): Instant = at(s.toDouble())

/** A fix `north` metres north of a fixed point, at `s` seconds. */
fun fix(s: Double, north: Double, accuracy: Double = 5.0, stationary: Boolean? = null): LocationFix =
    LocationFix(
        t = at(s), latitude = 52.5 + north / Geo.earthRadius * 180 / PI, longitude = 13.4,
        horizontalAccuracy = accuracy, stationary = stationary,
    )

fun fix(s: Int, north: Double, accuracy: Double = 5.0, stationary: Boolean? = null): LocationFix =
    fix(s.toDouble(), north, accuracy, stationary)

/** Start tapped at 0 s, the countdown ticks to 5 s: active from 5 s. `standingFix` adds a fix at 4 s at 0 m during the countdown. */
fun activeRun(standingFix: Boolean = false): RunHarness {
    val run = RunHarness(RunHarness.config)
    run.send(RunInput.User(UserAction.Start, at(0)))
    if (standingFix) run.send(RunInput.Fix(fix(4, north = 0.0)))
    for (s in 1..5) run.send(RunInput.Tick(at(s)))
    return run
}

/** Runs north at `speed` m/s, a fix every second from `from` to `to` (and a tick after each fix when `ticks`). Returns where it ended. */
fun walk(run: RunHarness, from: Int, to: Int, speed: Double = 3.0, startNorth: Double, ticks: Boolean = false): Double {
    var north = startNorth
    for (s in from..to) {
        north += speed
        run.send(RunInput.Fix(fix(s, north = north)))
        if (ticks) run.send(RunInput.Tick(at(s)))
    }
    return north
}

/** Only ticks, one per second: silence from the sensor and GPS. */
fun ticks(run: RunHarness, seconds: IntRange) {
    for (s in seconds) run.send(RunInput.Tick(at(s)))
}

/** A fix and a tick every second; `position(s)` is where the runner is at second `s`. */
fun move(run: RunHarness, seconds: IntRange, accuracy: (Int) -> Double = { 5.0 }, position: (Int) -> Double) {
    for (s in seconds) {
        run.send(RunInput.Fix(fix(s, north = position(s), accuracy = accuracy(s))))
        run.send(RunInput.Tick(at(s)))
    }
}
