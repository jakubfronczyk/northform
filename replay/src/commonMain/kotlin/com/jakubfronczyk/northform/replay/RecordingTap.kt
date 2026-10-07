package com.jakubfronczyk.northform.replay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Records a real run as a v1 replay fixture (`Replay/RecordingTap.swift`), so it can be replayed in
 * tests. Pure: the file write is injected (`sink` gets the whole JSONL text), so the class is common
 * and testable; the app's `Documents.write` is the sink on iOS.
 *
 * - Inputs arrive slightly out of time order across streams (a fix's own timestamp can be older than
 *   the last heart-rate sample); the file is written sorted by time, stable for equal times, as the
 *   format requires. Inputs before `meta.start` are dropped.
 * - The whole file is rewritten at most every [rewriteEvery] of run time and on [write], so a crash
 *   loses at most 30 s. A 2 h run is about 1.5 MB.
 *
 * `record` is called from the session's mailbox coroutine and `write` from the app: the state is a
 * `MutableStateFlow` updated with compare-and-set, the dependency-free atomic in commonMain.
 */
class RecordingTap(val meta: ReplayMeta, private val sink: (jsonl: String) -> Unit) {
    private class State(val inputs: List<ReplayInput>, val lastWrite: Instant?)

    private val state = MutableStateFlow(State(emptyList(), null))

    fun record(input: ReplayInput) {
        if (input.t < meta.start) return
        state.update { State(it.inputs + input, it.lastWrite) }
    }

    /** Called with the run's time (each tick): rewrites the file when [rewriteEvery] has passed. */
    fun tick(now: Instant) {
        var due = false
        state.update {
            val last = it.lastWrite
            when {
                last == null -> { due = false; State(it.inputs, now) }
                now - last >= rewriteEvery -> { due = true; State(it.inputs, now) }
                else -> { due = false; it }
            }
        }
        if (due) write()
    }

    /** Writes everything recorded so far, sorted by time (stable for equal times). */
    fun write() {
        val sorted = state.value.inputs.sortedBy { it.t } // sortedBy is stable
        sink(Fixture.encode(ReplayScript(meta, sorted)))
    }

    companion object {
        val rewriteEvery: Duration = 30.seconds
    }
}
