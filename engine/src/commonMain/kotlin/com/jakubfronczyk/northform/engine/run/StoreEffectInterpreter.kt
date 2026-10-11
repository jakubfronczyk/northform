package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.ports.RecordingStore

/**
 * Carries out ONE store effect against the [RecordingStore] and says what the reducer must hear back
 * (`RecordingSession.swift:255-277`). This is the production interpreter: [RecordingSession]'s writer
 * runs it for every effect in order, and the test harness runs the very same object, so a "rebuild =
 * live" test can never pass on two copies that merely agree (D94, D112).
 *
 * The seam is the return value: a flush answers `Persisted(seq)` or `PersistFailed(seq)` — a failed
 * write is not an error here, it is the reducer's business (its data stays buffered and is retried).
 * Create / stop / beginComputing answer nothing and the reducer never asks for them again, so they are
 * retried HERE: a failed one stays [pending] and is tried again before every later effect (and by
 * [retryPending]) until it lands. The store sees the effects in the order the reducer issued them:
 * while one is pending, a later create/stop/beginComputing queues behind it and a flush is not
 * attempted (it answers `PersistFailed`, so the reducer keeps its data). Without this a failed stop
 * left the run `Active` with no `endUtc`, `beginComputing` then failed too, and the next launch
 * offered Continue on a stopped run. Every failed attempt is reported to [onFailure]; the harness
 * rethrows so a test fails loudly.
 */
class StoreEffectInterpreter(
    private val store: RecordingStore,
    private val recordingId: RecordingId,
    private val onFailure: (RunEffect.Store, Throwable) -> Unit = { _, _ -> },
) {
    /** Create / stop / beginComputing the store has not taken yet, oldest first. */
    private val queue = ArrayDeque<RunEffect.Store>()

    /** Something the reducer asked for is not durable yet and is still being retried. */
    val pending: Boolean get() = queue.isNotEmpty()

    /** `when` as an expression over the sealed [RunEffect.Store]: a new store effect is a compile error (D4). */
    suspend fun perform(effect: RunEffect.Store): RunInput? {
        retryPending()
        return when (effect) {
            is RunEffect.Persist -> if (pending) {
                RunInput.PersistFailed(effect.seq) // behind a create/stop not written yet: not attempted, the reducer keeps it
            } else {
                try {
                    store.flush(effect.batch)
                    RunInput.Persisted(effect.seq)
                } catch (e: Exception) {
                    RunInput.PersistFailed(effect.seq)
                }
            }
            is RunEffect.Create, is RunEffect.Stop, RunEffect.BeginComputing -> {
                queue.addLast(effect)
                retryPending()
                null
            }
        }
    }

    /** Tries the queued effects in order and stops at the first that fails again. True when none is left. */
    suspend fun retryPending(): Boolean {
        while (true) {
            val next = queue.firstOrNull() ?: return true
            try {
                call(next)
            } catch (e: Exception) {
                onFailure(next, e)
                return false
            }
            queue.removeFirst()
        }
    }

    private suspend fun call(effect: RunEffect.Store) {
        when (effect) {
            is RunEffect.Create -> store.create(effect.recording)
            is RunEffect.Stop -> store.stop(recordingId, effect.at)
            RunEffect.BeginComputing -> store.beginComputing(recordingId)
            is RunEffect.Persist -> error("a flush is answered, never queued")
        }
    }
}
