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
 * Create / stop / beginComputing answer nothing; when one of them throws, [onFailure] hears it. Swift
 * only logged those (`RecordingSession.swift:259`), so production keeps that default; the harness
 * rethrows so a test fails loudly.
 */
class StoreEffectInterpreter(
    private val store: RecordingStore,
    private val recordingId: RecordingId,
    private val onFailure: (RunEffect.Store, Throwable) -> Unit = { _, _ -> },
) {
    /** `when` as an expression over the sealed [RunEffect.Store]: a new store effect is a compile error (D4). */
    suspend fun perform(effect: RunEffect.Store): RunInput? = when (effect) {
        is RunEffect.Create -> attempt(effect) { store.create(effect.recording) }
        is RunEffect.Persist -> try {
            store.flush(effect.batch)
            RunInput.Persisted(effect.seq)
        } catch (e: Exception) {
            RunInput.PersistFailed(effect.seq)
        }
        is RunEffect.Stop -> attempt(effect) { store.stop(recordingId, effect.at) }
        RunEffect.BeginComputing -> attempt(effect) { store.beginComputing(recordingId) }
    }

    /** An `inline` lambda inherits the caller's suspension, so `call` may hit the store. */
    private inline fun attempt(effect: RunEffect.Store, call: () -> Unit): RunInput? {
        try {
            call()
        } catch (e: Exception) {
            onFailure(effect, e)
        }
        return null
    }
}
