package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.SensorError
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Runs one recording live (`Engine/RecordingSession.swift`): feeds sensor, GPS, ticks and taps into
 * [RunReducer] and carries out its effects.
 *
 * The Swift `actor` becomes a **single-owner mailbox** (D5): every producer — the HR flow, the
 * location flow, sensor states, the 1 Hz tick, taps, `backgrounded`, and the writer's own
 * `Persisted`/`PersistFailed` — sends into ONE unbounded `Channel`; one coroutine receives, reduces
 * and routes the effects. The writer reporting back is just another send, so there is no
 * reentrancy and nothing to lock (the reason D5 rejected a `Mutex`). `end()` is a mail too, so the
 * answer is decided in the same coroutine that runs the countdown tick and cannot race it (D116).
 *
 * Store effects go to one writer coroutine that runs them strictly in order (create before any
 * flush, stop before the final batch); alerts have their own queue so a vibration never waits
 * behind a write (`RecordingSession.swift:7-10`).
 */
class RecordingSession(
    val config: RunConfig,
    private val sensor: HeartRateSensor,
    private val location: LocationProvider,
    private val store: RecordingStore,
    private val alerts: AlertPlayer,
    /** The run's time source; callers stamp taps with it so they share the run's time. */
    override val wallClock: WallClock,
    /** Sees every input in the order the reducer gets it (debug builds record runs with it). */
    private val inputObserver: ((RunInput) -> Unit)? = null,
) : RunSessionHandle {

    /** `RecordingSession.swift:86` — what closing the run did (D116). */
    enum class EndResult {
        /** Nothing was recorded (gate or countdown): the run is dropped and `run()` returns. */
        Discarded,
        /** The run started: it keeps going to its last write (cool-down, computing, D112). */
        Finishing,
    }

    /** What arrives in the mailbox: reducer inputs, and the one question answered inside it. */
    private sealed interface Mail {
        data class Input(val input: RunInput) : Mail
        data class End(val reply: CompletableDeferred<EndResult>) : Mail
    }

    private val inbox = Channel<Mail>(Channel.UNLIMITED)
    private val writes = Channel<RunEffect.Store>(Channel.UNLIMITED)
    private val alertQueue = Channel<Alert>(Channel.UNLIMITED)
    private val _snapshots = MutableStateFlow(RunState(config).snapshot)
    private val _routes = MutableStateFlow(Route())
    /** `run()` is single-shot (D112): the first caller takes this and never gives it back. */
    private val runOnce = Mutex()

    /** Every change of what the run screen shows (`RecordingSession.swift:67`). */
    override val snapshots: StateFlow<RunSnapshot> = _snapshots.asStateFlow()
    /** The drawn route: only fixes that counted, with breaks (`RecordingSession.swift:72`). */
    override val routes: StateFlow<Route> = _routes.asStateFlow()
    /** `RecordingSession.swift:76`. */
    val snapshot: RunSnapshot get() = _snapshots.value

    /** Set by the first `end()` (`RecordingSession.swift:39`); atomic because `end()` may read it from another coroutine. */
    private val ended = MutableStateFlow<EndResult?>(null)

    /** A tap, with the time the user made it: stamped by the caller, not on arrival (D91, D100). */
    override suspend fun send(action: UserAction, at: Instant) = receive(RunInput.User(action, at))

    /** The app went to the background at `t` (`RecordingSession.swift:111`). */
    suspend fun backgrounded(at: Instant) = receive(RunInput.Backgrounded(at))

    /**
     * `RecordingSession.swift:96` — the run screen closed. Decided inside the mailbox, so it can't
     * race the tick that ends the countdown and creates the recording. Asking again returns the
     * same answer — also after `run()` has returned, when the mailbox is gone and the answer is
     * read from what it left behind (a Swift actor method always answered).
     */
    suspend fun end(): EndResult {
        ended.value?.let { return it }
        val reply = CompletableDeferred<EndResult>()
        if (inbox.trySend(Mail.End(reply)).isClosed) return ended.value!! // sealed by the mailbox before it closed the inbox
        return reply.await()
    }

    /** `RecordingSession.swift:98`. */
    private fun decide(phase: RunPhase): EndResult = when (phase) {
        RunPhase.Gate, is RunPhase.Countdown -> EndResult.Discarded
        RunPhase.Active, is RunPhase.Paused, is RunPhase.Cooldown, RunPhase.Done -> EndResult.Finishing
    }

    /**
     * `RecordingSession.swift:119` — runs until the run is done and handed to computing, or until
     * cancelled (D112). Only the writer ends it: an input flow ending (HR `Unauthorized`, GPS,
     * sensor states) does not. On cancel the writer finishes the effect it is on and returns.
     * Single-shot: a second call returns at once. Structured: every loop is a child of this scope.
     */
    suspend fun run() {
        if (!runOnce.tryLock()) return
        coroutineScope {
            launch { mailbox() }
            launch { alertLoop() }
            launch { heartRateLoop() }
            launch { fixLoop() }
            launch { sensorStateLoop() }
            launch { tickLoop() }
            val writer = launch { writeLoop() }
            // The session waits for the writer alone (`RecordingSession.swift:135`), then tears the rest down.
            writer.join()
            alertQueue.close() // mirrors `:136`; the cancel below is what actually ends the loops
            coroutineContext.cancelChildren()
        }
    }

    // ── Reduce (the one coroutine that owns RunState) ──────────────────────────────────────

    private suspend fun mailbox() {
        var state = RunState(config)
        try {
            for (mail in inbox) {
                when (mail) {
                    is Mail.End -> {
                        val result = ended.value ?: decide(state.progress.phase).also {
                            ended.value = it
                            if (it == EndResult.Discarded) writes.close() // the writer ends, so `run()` returns
                        }
                        mail.reply.complete(result)
                    }
                    is Mail.Input -> {
                        if (ended.value == EndResult.Discarded) continue // nothing may be recorded after a discard (`:144`)
                        inputObserver?.invoke(mail.input)
                        val (next, effects) = RunReducer.reduce(state, mail.input)
                        state = next
                        for (effect in effects) when (effect) {
                            is RunEffect.Store -> writes.send(effect)
                            is RunEffect.PlayAlert -> alertQueue.send(effect.alert)
                            is RunEffect.Route -> _routes.value = _routes.value + effect.point
                        }
                        _snapshots.value = state.snapshot
                    }
                }
            }
        } finally {
            // The mailbox is gone with `run()`: seal the answer from the final state, then later taps are
            // dropped and later `end()`s read the seal (one coroutine decides, even in hindsight).
            if (ended.value == null) ended.value = decide(state.progress.phase)
            inbox.close()
            while (true) {
                val late = inbox.tryReceive().getOrNull() ?: break
                if (late is Mail.End) late.reply.complete(ended.value!!)
            }
        }
    }

    /** Unbounded, so this never suspends; after `run()` returned the inbox is closed and the input is dropped (`:144`). */
    private fun receive(input: RunInput) {
        inbox.trySend(Mail.Input(input))
    }

    // ── Loops (`RecordingSession.swift:242-325`) ────────────────────────────────────────────

    private suspend fun writeLoop() {
        val id = config.recordingId
        for (effect in writes) {
            if (!currentCoroutineContext().isActive) return
            // "On cancel the writer finishes the effect it is on and returns" (D112): the store call
            // itself is not interruptible; the check above is where cancellation takes effect.
            withContext(NonCancellable) {
                when (effect) {
                    is RunEffect.Create -> runCatching { store.create(effect.recording) } // the catch only logged in Swift
                    is RunEffect.Persist -> try {
                        store.flush(effect.batch)
                        receive(RunInput.Persisted(effect.seq))
                    } catch (e: Exception) {
                        receive(RunInput.PersistFailed(effect.seq))
                    }
                    is RunEffect.Stop -> runCatching { store.stop(id, effect.at) }
                    RunEffect.BeginComputing -> runCatching { store.beginComputing(id) }
                }
            }
            if (effect == RunEffect.BeginComputing) return // the last store effect: the run is over (`:277`)
        }
    }

    private suspend fun alertLoop() {
        for (alert in alertQueue) alerts.play(alert)
    }

    private suspend fun heartRateLoop() {
        try {
            sensor.heartRate().collect { receive(RunInput.Hr(it)) }
        } catch (e: SensorError) {
            // Only `Unauthorized` ends the stream; the run continues without HR (D112).
        }
    }

    private suspend fun fixLoop() {
        location.fixes().collect { receive(RunInput.Fix(it)) }
    }

    private suspend fun sensorStateLoop() {
        sensor.states().collect { receive(RunInput.Sensor(it.state, it.at)) }
    }

    /** `delay` runs on the injected dispatcher's clock (virtual in tests); the tick's time comes from the wall clock. */
    private suspend fun tickLoop() {
        while (true) {
            delay(1.seconds)
            receive(RunInput.Tick(wallClock.now()))
        }
    }
}
