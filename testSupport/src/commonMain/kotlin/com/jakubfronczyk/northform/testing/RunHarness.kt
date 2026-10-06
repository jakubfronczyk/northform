package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.engine.run.RunEffect
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunState
import com.jakubfronczyk.northform.engine.run.time
import com.jakubfronczyk.northform.engine.store.MemoryRecords
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Tests/EngineTests/RunHarness.swift` — plays the session's part with no coroutines and no clock:
 * merges inputs with 1 s ticks, runs store effects in order against [MemoryRecords], and feeds
 * confirmations back.
 *
 * `confirmAfter` models a slow store: store effects wait in a FIFO queue and run `confirmAfter`
 * inputs later, so new inputs arrive while a batch is in flight, as live. 0 = each store effect runs
 * and is confirmed within the input that caused it.
 */
class RunHarness(config: RunConfig, var confirmAfter: Int = 0) {
    /** The live state when a batch was cut, paired with the store right after that batch was flushed. */
    class Checkpoint(val state: RunState, val store: MemoryRecords) {
        val data: RecordingData get() = store.load(state.config.recordingId)!!
    }

    var state: RunState = RunState(config)
        private set
    var store = MemoryRecords()
        private set
    val alerts = ArrayList<Alert>()
    var route = Route()
        private set
    val checkpoints = ArrayList<Checkpoint>()
    /** Batches with these sequence numbers fail once. */
    val failing = HashSet<Int>()

    private class Pending(val effect: RunEffect.Store, val due: Int, val cut: RunState?)
    private val pending = ArrayDeque<Pending>()
    private var inputs = 0

    fun send(input: RunInput) {
        inputs += 1
        queue(reduce(input))
        runStoreEffects(dueBy = inputs)
    }

    /** Runs every store effect still waiting, in order (the end of a replay). */
    fun drain() = runStoreEffects(dueBy = Int.MAX_VALUE)

    private fun reduce(input: RunInput): List<RunEffect> {
        val reduced = RunReducer.reduce(state, input)
        state = reduced.state
        return reduced.effects
    }

    /** Alerts and route points happen at once; store effects wait their turn. */
    private fun queue(effects: List<RunEffect>) {
        for (effect in effects) when (effect) {
            is RunEffect.PlayAlert -> alerts += effect.alert
            is RunEffect.Route -> route += effect.point
            is RunEffect.Persist -> pending += Pending(effect, inputs + confirmAfter, cut = state)
            is RunEffect.Store -> pending += Pending(effect, inputs + confirmAfter, cut = null)
        }
    }

    private fun runStoreEffects(dueBy: Int) {
        while (true) {
            val next = pending.firstOrNull() ?: return
            if (next.due > dueBy) return
            pending.removeFirst()
            when (val effect = next.effect) {
                is RunEffect.Create -> store.create(effect.recording)
                is RunEffect.Persist -> if (failing.remove(effect.seq)) {
                    queue(reduce(RunInput.PersistFailed(effect.seq)))
                } else {
                    store.flush(effect.batch)
                    checkpoints += Checkpoint(next.cut!!, store.copy())
                    queue(reduce(RunInput.Persisted(effect.seq)))
                }
                is RunEffect.Stop -> store.stop(state.config.recordingId, effect.at)
                RunEffect.BeginComputing -> store.beginComputing(state.config.recordingId)
            }
        }
    }

    /** Everything stored for the run; tests that read it always have a recording. */
    val stored: RecordingData get() = store.load(state.config.recordingId)!!
    /** What the run has recorded so far: saved (if a recording exists yet), plus still waiting to be saved. */
    val recordedEvents: List<RecordingEvent> get() = (store.load(state.config.recordingId)?.events ?: emptyList()) + state.buffer.events
    val recordedFixes: List<LocationFix> get() = (store.load(state.config.recordingId)?.fixes ?: emptyList()) + state.buffer.fixes
    val recordedSplits: List<RecordingEvent> get() = recordedEvents.filter { it.kind is EventKind.Split }

    companion object {
        val config = RunConfig(
            recordingId = RecordingId("00000000-0000-0000-0000-000000000001"),
            type = RecordingType.Run,
            profile = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60),
            tzOffset = 7200,
        )

        /**
         * `RunHarness.swift:58` — script inputs plus a tick every second from `start` (shifted by
         * `tickOffset`) until `tail` after the last input. Sort key (t, rank, offset): ticks after
         * same-time inputs, input order otherwise — `sortedWith` is stable.
         */
        fun timeline(start: Instant, inputs: List<RunInput>, tail: Duration = 65.seconds, tickOffset: Duration = Duration.ZERO): List<RunInput> {
            class Item(val t: Instant, val rank: Int, val input: RunInput)
            val items = inputs.mapTo(ArrayList()) { Item(it.time!!, 0, it) }
            val end = (inputs.lastOrNull()?.time ?: start) + tail
            var tick = start + tickOffset
            while (tick <= end) {
                items += Item(tick, 1, RunInput.Tick(tick))
                tick += 1.seconds
            }
            return items.sortedWith(compareBy({ it.t }, { it.rank })).map { it.input }
        }

        fun play(
            start: Instant, inputs: List<RunInput>, failing: Set<Int> = emptySet(),
            tickOffset: Duration = Duration.ZERO, confirmAfter: Int = 0,
        ): RunHarness {
            val harness = RunHarness(config, confirmAfter)
            harness.failing += failing
            for (input in timeline(start, inputs, tickOffset = tickOffset)) harness.send(input)
            harness.drain()
            return harness
        }
    }
}
