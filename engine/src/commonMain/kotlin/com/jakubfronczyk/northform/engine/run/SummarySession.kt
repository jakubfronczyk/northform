package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RunTag
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
        /** On screen. [error] is the last Save's failure, shown inline; Save stays possible. */
        data class Ready(val summary: RunSummary, val error: String? = null) : State
        /** Save done: the screen closes. */
        data object Saved : State
    }

    private val _state = MutableStateFlow<State>(State.Computing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Computing → Ready. The reliability row is written before the screen shows, so a later Delete still keeps the drops counted. */
    suspend fun load() {
        val data = store.load(recordingId) ?: error("recording ${recordingId.value} not found")
        reliability.saveReliability(recordingId, ReliabilityRecord.of(data))
        _state.value = State.Ready(RunSummary.compute(data, profile))
    }

    /** Ready → Saved: `computing` → `saved` with the edits and the metric rows. A store failure stays Ready with the error. */
    suspend fun save(edits: SummaryEdits) {
        val ready = _state.value as? State.Ready ?: return
        runCatching { store.saveRunSummary(ready.summary, edits.tag, edits.rpe, edits.notes) }
            .onSuccess { _state.value = State.Saved }
            .onFailure { e -> _state.update { State.Ready(ready.summary, error = e.message ?: e.toString()) } }
    }
}
