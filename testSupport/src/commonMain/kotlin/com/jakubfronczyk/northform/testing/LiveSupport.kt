package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.RecordingSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// Helpers for driving the live `RecordingSession` in tests (the Swift `VirtualClock` + `AlertLog`).

/**
 * ONE virtual time for sleepers and stamps (D93c of the Swift build): `delay` runs on `runTest`'s
 * `TestCoroutineScheduler`, and this `WallClock` reads the same scheduler, so `advanceTimeBy` moves
 * both and the two can never disagree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.virtualWallClock(start: Instant): WallClock = WallClock { start + testScheduler.currentTime.milliseconds }

/** Every alert the session asked to be felt, in order. */
class RecordedAlerts : AlertPlayer {
    val played = ArrayList<Alert>()
    override suspend fun play(alert: Alert) { played += alert }
}

/** A tap stamped with the run's own clock, as the screens do (D100). */
suspend fun RecordingSession.tap(action: UserAction) = send(action, wallClock.now())
