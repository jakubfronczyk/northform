package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.ReliabilityStore
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the user adds on the Summary before Save (R27): the tag (null for a walk), RPE 0–10, notes. */
data class SummaryEdits(val tag: RunTag?, val rpe: Int?, val notes: String?)

/**
 * The Summary's lifecycle over the store (summary-screen spec): a recording in `computing` is loaded,
 * its reliability row written, its [RunSummary] computed, and the screen reads the [state]. Pure Kotlin
 * over the ports, no dispatcher of its own: the app launches its calls, tests call them directly.
 */
class SummarySession(
    val recordingId: RecordingId,
    private val store: RecordingStore,
    private val reliability: ReliabilityStore,
    private val profile: Profile,
) {
    sealed interface State {
        /** Loading and computing; what the screen shows first. */
        data object Computing : State
        /**
         * On screen. [busy] while a Save or Delete is at the store: both buttons are disabled and a second tap does
         * nothing. [error] is the last Save's failure, shown inline; Save stays possible.
         */
        data class Ready(val summary: RunSummary, val error: String? = null, val busy: Boolean = false) : State
        /** Save done: the screen closes. */
        data object Saved : State
        /** Delete done: the screen closes. */
        data object Deleted : State
    }

    private val _state = MutableStateFlow<State>(State.Computing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The recording as loaded, kept for [acceptHrMax]'s recompute. */
    private var data: RecordingData? = null

    /** Computing → Ready. The reliability row is written before the screen shows, so a later Delete still keeps the drops counted. */
    suspend fun load() {
        val loaded = store.load(recordingId) ?: error("recording ${recordingId.value} not found")
        reliability.saveReliability(recordingId, ReliabilityRecord.of(loaded))
        data = loaded
        _state.value = State.Ready(RunSummary.compute(loaded, profile))
    }

    /**
     * SU6: the "New max heart rate: 191 — Update zones?" card's Update. This Summary is recomputed with the higher
     * HRmax, so Save stores metrics carrying it (D24). The app's profile is not changed here: persisting it is the
     * Settings feature's. Nothing happens without a suggestion or while busy.
     */
    fun acceptHrMax() {
        val ready = _state.value as? State.Ready ?: return
        if (ready.busy) return
        val suggested = ready.summary.hrMaxSuggestion ?: return
        val loaded = data ?: return
        _state.value = State.Ready(RunSummary.compute(loaded, ready.summary.profile.copy(hrMax = suggested)))
    }

    /**
     * Ready → Saved: `computing` → `saved` with the edits and the metric rows. A walk stores no tag (the chips are not
     * shown for one). A store failure stays Ready with the error; a tap while busy does nothing.
     */
    suspend fun save(edits: SummaryEdits) {
        val ready = claim() ?: return
        val tag = if (ready.summary.recording.type == RecordingType.Walk) null else edits.tag
        runCatching { store.saveRunSummary(ready.summary, tag, edits.rpe, edits.notes) }
            .onSuccess { _state.value = State.Saved }
            .onFailure { e -> _state.value = State.Ready(ready.summary, error = e.message ?: e.toString()) }
    }

    /** Ready → Deleted (R27: after the screen's confirm sheet): the recording is removed. A store failure stays Ready with the error. */
    suspend fun delete() {
        val ready = claim() ?: return
        runCatching { store.delete(recordingId) }
            .onSuccess { _state.value = State.Deleted }
            .onFailure { e -> _state.value = State.Ready(ready.summary, error = e.message ?: e.toString()) }
    }

    /** Takes the Ready state for one store call: null when not Ready or already busy (the double tap). */
    private fun claim(): State.Ready? {
        var claimed: State.Ready? = null
        _state.update { s ->
            if (s is State.Ready && !s.busy) s.copy(busy = true, error = null).also { claimed = s } else s
        }
        return claimed
    }
}
