package com.jakubfronczyk.northform.ui

import kotlin.time.Instant

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

    /** The rows newest first, "N recordings", and "Nothing here yet" when nothing is saved. */
    data class Listed(val count: String, val rows: List<RecordingRow>, val emptyLine: String?) : HistoryModel

    companion object {
        /** @param now and [tzOffset] are the phone's (the year rule); each row's Day is in its own zone. */
        fun from(saved: SavedList, now: Instant, tzOffset: Int): HistoryModel = when (saved) {
            SavedList.Loading -> Loading
            is SavedList.Failed -> Failed
            is SavedList.Loaded -> {
                val rows = saved.recordings.map { RecordingRow.of(it, now, tzOffset) }
                Listed(
                    count = "${rows.size} ${if (rows.size == 1) "recording" else "recordings"}",
                    rows = rows,
                    emptyLine = if (rows.isEmpty()) "Nothing here yet" else null,
                )
            }
        }
    }
}
