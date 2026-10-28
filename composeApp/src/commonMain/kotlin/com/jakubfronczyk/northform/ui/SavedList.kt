package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.ports.SavedRecording

/**
 * The app's one list of saved recordings (history-screen spec): every one, newest first, read at launch and after
 * every Save or Delete. Today shows the first three of [recordings] whatever the state; History shows nothing while
 * [Loading] and "Couldn't load history" when [Failed].
 */
sealed interface SavedList {
    /** The last list read: empty before the first. */
    val recordings: List<SavedRecording>

    /** This list with [id] taken off, in the same state (a Delete, before the re-read answers). */
    fun without(id: RecordingId): SavedList = when (this) {
        Loading -> this
        is Loaded -> Loaded(recordings.filter { it.recording.id != id })
        is Failed -> copy(recordings = recordings.filter { it.recording.id != id })
    }

    /** Nothing read yet. */
    data object Loading : SavedList {
        override val recordings: List<SavedRecording> = emptyList()
    }

    data class Loaded(override val recordings: List<SavedRecording>) : SavedList

    /** The last read failed (the app logs why); [recordings] is the list before it. */
    data class Failed(override val recordings: List<SavedRecording>) : SavedList
}
