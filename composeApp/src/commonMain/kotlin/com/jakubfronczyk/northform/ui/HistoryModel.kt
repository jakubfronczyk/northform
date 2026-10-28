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

    /**
     * The rows that match [filter] newest first, "N recordings" of them, and the empty line: "Nothing here yet" when
     * nothing is saved, "No recordings match these filters" when the filters hide everything. [showsTags]: not for walks.
     */
    data class Listed(val filter: HistoryFilter, val count: String, val rows: List<RecordingRow>, val emptyLine: String?) : HistoryModel {
        val showsTags: Boolean get() = filter.type != RecordingType.Walk
    }

    companion object {
        /** @param now and [tzOffset] are the phone's (the year rule); each row's Day is in its own zone. */
        fun from(saved: SavedList, filter: HistoryFilter, now: Instant, tzOffset: Int): HistoryModel = when (saved) {
            SavedList.Loading -> Loading
            is SavedList.Failed -> Failed
            is SavedList.Loaded -> {
                val rows = saved.recordings.filter { filter.matches(it.recording) }.map { RecordingRow.of(it, now, tzOffset) }
                Listed(
                    filter = filter,
                    count = "${rows.size} ${if (rows.size == 1) "recording" else "recordings"}",
                    rows = rows,
                    emptyLine = when {
                        saved.recordings.isEmpty() -> "Nothing here yet"
                        rows.isEmpty() -> "No recordings match these filters"
                        else -> null
                    },
                )
            }
        }
    }
}
