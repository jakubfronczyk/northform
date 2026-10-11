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
 * and routes the effects. Because that coroutine also sees the writer's answers, it is the one that
 * knows when the batch a `backgrounded` asked for is durable (persistence/02). The writer reporting back is just another send, so there is no
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
    /**
     * Where the run starts: a fresh gate by default, or a state rebuilt from the store (crash resume,
     * R28). The reducer never knows the difference; `config` must be `initial.config`.
     */
    private val initial: RunState = RunState(config),
) : RunSessionHandle {
    init { require(initial.config == config) { "initial state belongs to another run" } }

    /** What arrives in the mailbox: reducer inputs, and the one question answered inside it. */
    private sealed interface Mail {
        data class Input(val input: RunInput) : Mail
        data class End(val reply: CompletableDeferred<EndResult>) : Mail
        /** [RunInput.Backgrounded], plus a reply completed once the store answered the batch it asked for. */
        data class Backgrounded(val at: Instant, val answered: CompletableDeferred<Unit>) : Mail
    }

    private val inbox = Channel<Mail>(Channel.UNLIMITED)
    private val writes = Channel<RunEffect.Store>(Channel.UNLIMITED)
    private val alertQueue = Channel<Alert>(Channel.UNLIMITED)
    private val _snapshots = MutableStateFlow(initial.snapshot)
    private val _routes = MutableStateFlow(Route())
    /** `run()` is single-shot (D112): the first caller takes this and never gives it back. */
    private val runOnce = Mutex()
    /** Written only by the writer, which hears every store outcome first-hand (persistence/02). */
    private val _notSaving = MutableStateFlow(false)
    /**
     * The one store-effect interpreter (also what the test harness runs). A failed create/stop/computing
     * cannot be retried by seq; it is rethrown so the writer marks the run "not saving" (Swift logged it).
     */
    private val storeEffects = StoreEffectInterpreter(store, config.recordingId, onFailure = { _, e -> throw e })

    /** Every change of what the run screen shows (`RecordingSession.swift:67`). */
    override val snapshots: StateFlow<RunSnapshot> = _snapshots.asStateFlow()
    /** The drawn route: only fixes that counted, with breaks (`RecordingSession.swift:72`). */
    override val routes: StateFlow<Route> = _routes.asStateFlow()
    /** `RecordingSession.swift:76`. */
    val snapshot: RunSnapshot get() = _snapshots.value
    /** persistence/02: the last store write failed; cleared by the next confirmed flush. */
    override val notSaving: StateFlow<Boolean> = _notSaving.asStateFlow()

    /** Set by the first `end()` (`RecordingSession.swift:39`); atomic because `end()` may read it from another coroutine. */
    private val ended = MutableStateFlow<EndResult?>(null)

    /** A tap, with the time the user made it: stamped by the caller, not on arrival (D91, D100). */
    override suspend fun send(action: UserAction, at: Instant) = receive(RunInput.User(action, at))

    /**
     * The app went to the background at `at` (`RecordingSession.swift:111`). Returns once the store
     * answered the batch this asked for — confirmed or failed — or at once when nothing needs saving
     * (before start, after a discard, after `run()` returned). Never outlives `run()`.
     */
    override suspend fun backgrounded(at: Instant) {
        val answered = CompletableDeferred<Unit>()
        if (inbox.trySend(Mail.Backgrounded(at, answered)).isClosed) return
        answered.await()
    }

    /**
     * `RecordingSession.swift:96` — the run screen closed. Decided inside the mailbox, so it can't
     * race the tick that ends the countdown and creates the recording. Asking again returns the
     * same answer — also after `run()` has returned, when the mailbox is gone and the answer is
     * read from what it left behind (a Swift actor method always answered).
     */
    override suspend fun end(): EndResult {
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
        try {
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
        } finally {
            // A child cancelled before it was first dispatched never runs its body, `finally` included. When
            // `run()` is cancelled that early the mailbox never sealed, and every `backgrounded`/`end` would
            // wait on an inbox nobody reads. `coroutineScope` returns only after every child finished, so by
            // here a mailbox that did start has sealed already, and this is a no-op.
            seal(initial)
        }
    }

    /** Set once by [seal]; read and written only by the mailbox and then by `run()` after it, never at once. */
    private var sealed = false

    /**
     * The mailbox is gone with `run()`: seal the answer from the final state, then later taps are
     * dropped and later `end()`s read the seal (one coroutine decides, even in hindsight). Nothing
     * will be answered any more, so no `backgrounded` waits on it.
     */
    private fun seal(last: RunState, waiting: List<Pair<Int, CompletableDeferred<Unit>>> = emptyList()) {
        if (sealed) return
        sealed = true
        if (ended.value == null) ended.value = decide(last.progress.phase)
        inbox.close()
        waiting.forEach { (_, answered) -> answered.complete(Unit) }
        while (true) {
            when (val late = inbox.tryReceive().getOrNull() ?: break) {
                is Mail.End -> late.reply.complete(ended.value!!)
                is Mail.Backgrounded -> late.answered.complete(Unit)
                is Mail.Input -> Unit
            }
        }
    }

    // ── Reduce (the one coroutine that owns RunState) ──────────────────────────────────────

    private suspend fun mailbox() {
        var state = initial
        /** `backgrounded` callers, each with the seq of the flush that carries its batch. */
        val waiting = ArrayList<Pair<Int, CompletableDeferred<Unit>>>()

        /** Reduces one input and routes its effects; false when it was dropped after a discard (`:144`). */
        suspend fun step(input: RunInput): Boolean {
            if (ended.value == EndResult.Discarded) return false
            inputObserver?.invoke(input)
            val (next, effects) = RunReducer.reduce(state, input)
            state = next
            for (effect in effects) when (effect) {
                is RunEffect.Store -> writes.send(effect)
                is RunEffect.PlayAlert -> alertQueue.send(effect.alert)
                is RunEffect.Route -> _routes.value = _routes.value + effect.point
            }
            _snapshots.value = state.snapshot
            val answeredSeq = when (input) {
                is RunInput.Persisted -> input.seq
                is RunInput.PersistFailed -> input.seq
                else -> null
            }
            if (answeredSeq != null) waiting.removeAll { (seq, answered) -> (seq <= answeredSeq).also { if (it) answered.complete(Unit) } }
            return true
        }

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
                    is Mail.Input -> step(mail.input)
                    is Mail.Backgrounded -> {
                        val buffer = state.buffer
                        // The batch is the persist this input issued, or — with one in flight — the next seq,
                        // which the reducer issues once that one is answered. Neither: nothing to save.
                        val seq = when {
                            !step(RunInput.Backgrounded(mail.at)) -> null
                            state.buffer.nextSeq != buffer.nextSeq -> state.buffer.nextSeq - 1
                            state.buffer.inFlight != null -> state.buffer.nextSeq
                            else -> null
                        }
                        if (seq == null) mail.answered.complete(Unit) else waiting += seq to mail.answered
                    }
                }
            }
        } finally {
            seal(state, waiting)
        }
    }

    /** Unbounded, so this never suspends; after `run()` returned the inbox is closed and the input is dropped (`:144`). */
    private fun receive(input: RunInput) {
        inbox.trySend(Mail.Input(input))
    }

    // ── Loops (`RecordingSession.swift:242-325`) ────────────────────────────────────────────

    private suspend fun writeLoop() {
        for (effect in writes) {
            if (!currentCoroutineContext().isActive) return
            // "On cancel the writer finishes the effect it is on and returns" (D112): the store call
            // itself is not interruptible; the check above is where cancellation takes effect.
            // The writer reporting back (`Persisted`/`PersistFailed`) is just another mail (D5). It is
            // also the one place the store's health is written: a flush answers it, a thrown
            // create/stop/computing marks it failed, and nothing else clears it (persistence/02).
            withContext(NonCancellable) {
                val outcome = runCatching { storeEffects.perform(effect) }
                _notSaving.value = outcome.fold(
                    onSuccess = { answer ->
                        when (answer) {
                            is RunInput.Persisted -> false
                            is RunInput.PersistFailed -> true
                            else -> _notSaving.value
                        }
                    },
                    onFailure = { true },
                )
                outcome.getOrNull()?.let(::receive)
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
