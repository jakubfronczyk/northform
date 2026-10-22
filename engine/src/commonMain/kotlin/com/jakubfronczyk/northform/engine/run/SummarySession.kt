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
 * its reliability row written, its [RunSummary] computed, and the screen reads the [state] and the
 * [edits]. Pure Kotlin over the ports, no dispatcher of its own: the app launches its calls, tests call
 * them directly.
 */
class SummarySession(
    val recordingId: RecordingId,
    private val store: RecordingStore,
    private val reliability: ReliabilityStore,
    private val profile: Profile,
) {
    /** Where the Summary is in its life: what the screen draws (summary-screen/01, 05). */
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
        /**
         * The recording could not be loaded or computed: "Couldn't compute this run", Retry and Delete (never a silent
         * return to Today). [type] is known when the load worked and only the compute failed. [busy] as in [Ready].
         */
        data class Failed(val reason: String, val type: RecordingType? = null, val busy: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Computing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _edits = MutableStateFlow(SummaryEdits(tag = null, rpe = null, notes = null))
    /** The user's edits so far (R27), the suggested tag preselected (D76); the screen draws them and sends changes through [edit]. */
    val edits: StateFlow<SummaryEdits> = _edits.asStateFlow()

    /** The recording as loaded, kept for [acceptHrMax]'s recompute. */
    private var data: RecordingData? = null

    /**
     * Computing → Ready, or Failed when the store has no such recording, throws, or the data cannot be computed. The
     * reliability row is written before the compute, so a run that never computes and is deleted still counts its
     * drops; it is a cache of the data (R29), so a write that fails costs a recompute later, never the Summary.
     */
    suspend fun load() {
        _state.value = runCatching {
            val loaded = store.load(recordingId) ?: error("recording ${recordingId.value} not found")
            data = loaded
            runCatching { reliability.saveReliability(recordingId, ReliabilityRecord.of(loaded)) }
            val summary = runCatching { RunSummary.compute(loaded, profile) }
                .getOrElse { e -> return@runCatching State.Failed(e.message ?: e.toString(), type = loaded.recording.type) }
            _edits.value = SummaryEdits(tag = summary.suggestedTag, rpe = null, notes = null)
            State.Ready(summary)
        }.getOrElse { e -> State.Failed(e.message ?: e.toString()) }
    }

    /** Failed → Computing → [load] again (the Retry button). */
    suspend fun retry() {
        if (_state.value !is State.Failed) return
        _state.value = State.Computing
        load()
    }

    /** A change on the "Your run" card; nothing reaches the store until [save]. */
    fun edit(edits: SummaryEdits) {
        _edits.value = edits
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
     * Ready → Saved: `computing` → `saved` with the [edits] and the metric rows. A walk stores no tag (the chips are
     * not shown for one). A store failure stays Ready with the error; a tap while busy does nothing.
     */
    suspend fun save() {
        val ready = claim() as? State.Ready ?: return
        val edits = _edits.value
        val tag = if (ready.summary.recording.type == RecordingType.Walk) null else edits.tag
        runCatching { store.saveRunSummary(ready.summary, tag, edits.rpe, edits.notes) }
            .onSuccess { _state.value = State.Saved }
            .onFailure { e -> _state.value = State.Ready(ready.summary, error = e.message ?: e.toString()) }
    }

    /**
     * Ready or Failed → Deleted (R27: after the screen's confirm sheet): the recording is removed. A store failure
     * stays where it was, with the error.
     */
    suspend fun delete() {
        val from = claim() ?: return
        runCatching { store.delete(recordingId) }
            .onSuccess { _state.value = State.Deleted }
            .onFailure { e ->
                val reason = e.message ?: e.toString()
                _state.value = when (from) {
                    is State.Ready -> State.Ready(from.summary, error = reason)
                    is State.Failed -> State.Failed(reason, type = from.type)
                    else -> from
                }
            }
    }

    /**
     * Takes the state for one store call, marking it busy: null when already busy (the double tap) or in a state with
     * nothing to do. Save needs Ready; Delete takes Ready or Failed (the way out of a run that will never compute).
     */
    private fun claim(): State? {
        var claimed: State? = null
        _state.update { s ->
            when {
                s is State.Ready && !s.busy -> s.copy(busy = true, error = null).also { claimed = s }
                s is State.Failed && !s.busy -> s.copy(busy = true).also { claimed = s }
                else -> s
            }
        }
        return claimed
    }
}
