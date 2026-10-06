package com.jakubfronczyk.northform.engine.run

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlin.time.Duration.Companion.seconds

/** The store as the session sees it: intent methods only. Spike: the one call the mailbox needs. */
interface RunWriter {
    /** Returns normally when durable; throws when not. The session turns the result into `Persisted`/`PersistFailed`. */
    suspend fun persist(seq: Int, batch: FlushBatch)
    suspend fun stop(at: kotlin.time.Instant)
}

/**
 * The run session as a single-owner mailbox (D5). Every producer sends `RunInput` into one unbounded
 * `Channel`; ONE coroutine owns `RunState`, reduces, and routes effects. The write loop reports back by
 * SENDING `Persisted(seq)` into the same inbox — never by calling a method that mutates state. That is
 * why no `Mutex` exists here: `kotlinx.coroutines.sync.Mutex` is not reentrant and the Swift actor's
 * self-call pattern would deadlock on it (kotlin-architect review, blocker #2).
 *
 * Channels: `Channel.UNLIMITED` for anything that must not drop (inputs, writes); `StateFlow` only for
 * views that may conflate (the snapshot).
 */
class RecordingSession(
    private val sensor: HeartRateSensor,
    private val writer: RunWriter,
    private val wallClock: WallClock,
) {
    private val inbox = Channel<RunInput>(Channel.UNLIMITED)
    private val writes = Channel<RunEffect>(Channel.UNLIMITED)
    private val alerts = Channel<RunEffect.Alert>(Channel.UNLIMITED)
    private val _snapshot = MutableStateFlow(RunState())

    /** A conflatable view: the screen only ever needs the latest state; equal states are dropped by StateFlow. */
    val snapshot: StateFlow<RunState> = _snapshot.asStateFlow()

    /** A tap, stamped by the caller with the run's clock (time is data). */
    suspend fun send(action: UserAction) = inbox.send(RunInput.User(action, wallClock.now()))

    /**
     * Runs until the writer ends (after the final persist) or cancellation. Structured concurrency:
     * producers are children of this scope; cancelling `run()` tears all of them down.
     */
    suspend fun run(): Unit = coroutineScope {
        launch { sensor.heartRate().collect { inbox.send(RunInput.Hr(it)) } }
        launch { sensor.states().collect { inbox.send(RunInput.Sensor(it.state, it.at)) } }
        launch { while (true) { delay(1.seconds); inbox.send(RunInput.Tick(wallClock.now())) } }
        val writerJob = launch { writeLoop() }
        launch { for (a in alerts) { /* TODO(spike): AlertPlayer port (D14) — the decision is here, delivery is the adapter */ } }

        launch {
            var state = RunState()
            for (input in inbox) {
                val (next, effects) = RunReducer.reduce(state, input)
                state = next
                _snapshot.value = next
                for (e in effects) when (e) {
                    is RunEffect.Persist, is RunEffect.Stop -> writes.send(e)
                    is RunEffect.Alert -> alerts.send(e)
                }
                if (next.phase is Phase.Done && next.inFlight == null && next.unconfirmedHr.isEmpty()) writes.close()
            }
        }
        writerJob.join()
        // The writer ending IS the end of the run (D112 of the Swift build): tear the producers down.
        coroutineContext.cancelChildren()
    }

    private suspend fun writeLoop() {
        for (effect in writes) when (effect) {
            is RunEffect.Persist -> try {
                writer.persist(effect.seq, effect.batch)
                inbox.send(RunInput.Persisted(effect.seq))
            } catch (e: Exception) {
                inbox.send(RunInput.PersistFailed(effect.seq))
            }
            is RunEffect.Stop -> writer.stop(effect.at)
            is RunEffect.Alert -> error("alerts never reach the writer")
        }
    }
}
