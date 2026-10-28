package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import kotlin.time.Instant

/**
 * History's filters (T1, history-screen/02): a type (null = All) and a run tag, combined with AND. Walks have no tag,
 * so picking Walk clears it; tapping the selected tag again clears it. Held by the root, reset when History opens.
 */
data class HistoryFilter(val type: RecordingType? = null, val tag: RunTag? = null) {
    /** All / Run / Walk. */
    fun pick(type: RecordingType?) = HistoryFilter(type, tag.takeIf { type != RecordingType.Walk })

    /** A tag pill: on, or off when it was the one on. */
    fun toggle(tag: RunTag) = copy(tag = tag.takeIf { it != this.tag })

    fun matches(r: Recording) = (type == null || type == r.type) && (tag == null || tag == r.tag)
}

/**
 * Everything the History artboard (`Home.dc.html`) shows, as plain values (history-screen spec): one pure function of
 * the app's [SavedList] and now, so the screen only lays out and the rules are table-tested without Compose.
 */
sealed interface HistoryModel {
    /** The first read has not answered: nothing is shown (one local query). */
    data object Loading : HistoryModel

    /** "Couldn't load history" with Retry: never "Nothing here yet", which would say there are no recordings. */
    data object Failed : HistoryModel {
        const val line = "Couldn't load history"
    }

    /** The rows that match [filter] newest first, "N recordings" of them, and why it is [empty], if it is. [showsTags]: not for walks. */
    data class Listed(val filter: HistoryFilter, val count: String, val rows: List<RecordingRow>, val empty: Empty?) : HistoryModel {
        val showsTags: Boolean get() = filter.type != RecordingType.Walk
    }

    /** Which of the artboard's two empty lines, so the screen styles it without deciding it. */
    enum class Empty(val line: String) {
        /** Nothing is saved at all, whatever the filters: the title-style line. */
        NothingSaved("Nothing here yet"),
        /** The filters hide every saved recording: the grey line. */
        NoMatch("No recordings match these filters"),
    }

    companion object {
        /** @param now decides the year rule, read in each recording's own zone like its Day. */
        fun from(saved: SavedList, filter: HistoryFilter, now: Instant): HistoryModel = when (saved) {
            SavedList.Loading -> Loading
            is SavedList.Failed -> Failed
            is SavedList.Loaded -> {
                val rows = saved.recordings.filter { filter.matches(it.recording) }.map { RecordingRow.of(it, yearAsOf = now) }
                Listed(
                    filter = filter,
                    count = "${rows.size} ${if (rows.size == 1) "recording" else "recordings"}",
                    rows = rows,
                    empty = when {
                        saved.recordings.isEmpty() -> Empty.NothingSaved
                        rows.isEmpty() -> Empty.NoMatch
                        else -> null
                    },
                )
            }
        }
    }
}
