package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * `Engine/RunSessionHandle.swift` (D110 of the Swift build): what the run screens need from a
 * recording session — follow what it shows, send the user's taps. Screens depend on this, not on
 * [RecordingSession], so previews and tests can use a fake.
 *
 * Swift `AsyncStream` per subscriber → Kotlin `StateFlow`: a new collector gets the current value
 * first, then every change (the Broadcaster's `.state` semantics).
 */
/** `RecordingSession.swift:86` — what closing the run did (D116). */
enum class EndResult {
    /** Nothing was recorded (gate or countdown): the run is dropped and the session returns. */
    Discarded,
    /** The run started: it keeps going to its last write (cool-down, computing, D112). */
    Finishing,
}

interface RunSessionHandle {
    /** The run's time source: taps are stamped with it before any hop (D91, D100). */
    val wallClock: WallClock
    /** Every change of what the run screen shows. */
    val snapshots: StateFlow<RunSnapshot>
    /** The drawn route. */
    val routes: StateFlow<Route>
    /** A tap, with the time the user made it. */
    suspend fun send(action: UserAction, at: Instant)
    /** The run screen closed; the session decides what that means (D116). Idempotent. */
    suspend fun end(): EndResult
    /** The app went to the background at `at`: save what is buffered now (D94). */
    suspend fun backgrounded(at: Instant)
}
