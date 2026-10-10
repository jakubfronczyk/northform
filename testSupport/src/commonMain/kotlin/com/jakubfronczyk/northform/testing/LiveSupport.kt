package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
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
suspend fun RunSessionHandle.tap(action: UserAction) = send(action, wallClock.now())

/**
 * The production in-memory store with a switch that fails the next flush once ("disk full"). Kotlin
 * interface delegation (`by inner`) forwards every other port method untouched.
 */
class FlakyStore(val inner: InMemoryRecordingStore = InMemoryRecordingStore()) : RecordingStore by inner {
    var failNext = false
    override suspend fun flush(batch: FlushBatch) {
        if (failNext) {
            failNext = false
            error("disk full")
        }
        inner.flush(batch)
    }
}

/**
 * Runs a `suspend` call that never actually suspends (the in-memory store takes an uncontended
 * `Mutex`) to its end with no dispatcher and no clock: `startCoroutine` on an empty context, the
 * continuation captures the result. Keeps the reducer harness synchronous while it runs the
 * production effect interpreter; a call that did suspend is a bug and fails here.
 */
fun <T> runSynchronously(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the call suspended: the harness is synchronous" }.getOrThrow()
}

/**
 * Loads a finished recording into a fresh store through the port's own steps (create → one flush →
 * stop → beginComputing), so a summary test can start from `computing` on any store without replaying
 * the run again. [data] must be stopped; its events are flushed without the `Stop` one, which `stop` writes.
 */
suspend fun RecordingStore.seedComputing(data: RecordingData) {
    val recording = data.recording
    val endUtc = requireNotNull(recording.endUtc) { "seedComputing needs a stopped recording" }
    create(recording.copy(state = RecordingState.Active, endUtc = null, tag = null, rpe = null, notes = null))
    flush(FlushBatch(recording.id, data.hr, data.fixes, data.events.filter { it.kind != EventKind.Stop }, persistedAt = data.lastPersistedAt ?: endUtc))
    stop(recording.id, endUtc)
    beginComputing(recording.id)
}
