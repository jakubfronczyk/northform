package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.ports.SavedRecording

/**
 * The app's one list of saved recordings (history-screen spec): every one, newest first, read at launch and after
 * every Save or Delete. Today shows the first three of [recordings] whatever the state; History shows nothing while
 * [Loading] and "Couldn't load history" when [Failed].
 */
sealed interface SavedList {
    /** The last list read: empty before the first. */
    val recordings: List<SavedRecording>

    /** Nothing read yet. */
    data object Loading : SavedList {
        override val recordings: List<SavedRecording> = emptyList()
    }

    data class Loaded(override val recordings: List<SavedRecording>) : SavedList

    /** The last read failed; [recordings] is the list before it. */
    data class Failed(override val recordings: List<SavedRecording>, val reason: String) : SavedList
}
