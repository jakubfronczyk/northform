package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.SensorStateChange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one way a [HeartRateSensor] implementation keeps the port's `states()` promise (D36): every change
 * delivered, in order, none merged, starting with the current value. Used by `GattHeartRateSensor`, the
 * replay timeline and the scripted test fake, so the rule lives here once.
 *
 * Idiom: a `MutableSharedFlow(replay = 1)` (the late collector gets the current value) with a bounded extra
 * buffer, never a conflating `StateFlow` (a drop's `Lost` → `Connecting` at one instant would reach a slow
 * collector as `Connecting` only). [publish] uses `tryEmit` under `check`: a collector [bufferSize] changes
 * behind is a stall, and it surfaces as an exception instead of an unbounded buffer growing silently.
 *
 * Not thread-safe: each implementation publishes from one place (Gatt's mailbox coroutine, the timeline's player).
 */
class SensorStateBroadcaster(initial: SensorStateChange) {
    private val flow = MutableSharedFlow<SensorStateChange>(replay = 1, extraBufferCapacity = bufferSize)

    /** The current state: what a new collector starts with and what [publish] compares against. */
    var latest: SensorStateChange = initial
        private set

    init { check(flow.tryEmit(initial)) { "the first state cannot be refused" } }

    /** Every change in order, starting with the current one: what `HeartRateSensor.states()` returns. */
    val changes: SharedFlow<SensorStateChange> = flow.asSharedFlow()

    /**
     * One change, delivered as given. The same state as [latest] is no change (D100) whatever its time: it keeps
     * the first time and emits nothing. Returns whether it was delivered.
     */
    fun publish(change: SensorStateChange): Boolean {
        if (change.state == latest.state) return false
        latest = change
        check(flow.tryEmit(change)) { "a sensor-state collector stalled $bufferSize changes behind (D36)" }
        return true
    }

    companion object {
        /** Changes a collector may fall behind before [publish] fails: a link flaps a few times a minute at worst. */
        const val bufferSize = 64
    }
}
