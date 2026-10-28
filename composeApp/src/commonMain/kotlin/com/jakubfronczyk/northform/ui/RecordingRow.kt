package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.ui.run.SummaryModel
import kotlin.time.Instant

/**
 * One saved recording as a list row, the same on Today's Recent and in History: "Run · Long", "Sun 28 Sep · 08:30" on
 * its Day, "16.80" km, "1:32:05 · 148 bpm"; [type] colours the dot. History adds the year for an earlier year.
 */
data class RecordingRow(val id: RecordingId, val type: RecordingType, val title: String, val date: String, val distance: String, val detail: String) {
    companion object {
        /**
         * @param yearAsOf History's year rule (history-screen spec): the year shows when the recording started in an
         * earlier year than this moment, both read in the recording's own zone. Null (Today): never.
         */
        fun of(s: SavedRecording, yearAsOf: Instant? = null): RecordingRow {
            val r = s.recording
            val h = s.headline
            val earlierYear = yearAsOf != null && RunFormat.year(r.startUtc, r.tzOffset) < RunFormat.year(yearAsOf, r.tzOffset)
            return RecordingRow(
                r.id, r.type,
                title = listOfNotNull(SummaryModel.title(r.type), r.tag?.name).joinToString(" · "),
                date = "${RunFormat.date(r.startUtc, r.tzOffset, withYear = earlierYear)} · ${RunFormat.localTime(r.startUtc, r.tzOffset)}",
                distance = RunFormat.distance(h.distance),
                detail = listOfNotNull(RunFormat.time(h.totalTime), RunFormat.bpm(h.avgHr)).joinToString(" · "),
            )
        }
    }
}
