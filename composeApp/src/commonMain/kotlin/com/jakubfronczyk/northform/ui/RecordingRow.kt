package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.ui.run.SummaryModel
import kotlin.time.Instant

/**
 * One saved recording as a list row, the same on Today's Recent and in History: "Run · Long", "Sun 28 Sep · 08:30" on
 * its Day (with the year when it is from an earlier year than now), "16.80" km, "1:32:05 · 148 bpm"; [type] colours the dot.
 */
data class RecordingRow(val id: RecordingId, val type: RecordingType, val title: String, val date: String, val distance: String, val detail: String) {
    companion object {
        /** @param now and [tzOffset] are the phone's: they decide whether the year shows. */
        fun of(s: SavedRecording, now: Instant, tzOffset: Int): RecordingRow {
            val r = s.recording
            val h = s.headline
            val earlierYear = RunFormat.year(r.startUtc, r.tzOffset) < RunFormat.year(now, tzOffset)
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
