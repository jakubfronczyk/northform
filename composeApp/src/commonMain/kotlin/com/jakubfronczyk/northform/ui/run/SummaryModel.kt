package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.engine.run.RunSummary
import com.jakubfronczyk.northform.ui.RunFormat

/**
 * Everything the SummaryDark artboard's top block shows, as plain strings: one pure function of the
 * [RunSummary], so the screen only lays out and the rules are table-tested without Compose (the
 * [LiveRunModel] pattern). The summary reads only `RunSummary` (engine/04).
 */
data class SummaryModel(
    /** The type pill: "Run" / "Walk". */
    val typeLabel: String,
    /** "Tue 30 Sep · 07:12", in the recording's own zone. */
    val dateLine: String,
    val distance: String,
    /** Stop − start, pauses included (D28). */
    val totalTime: String,
    /** "moving m:ss": the active time. */
    val movingLine: String,
    val avgPace: String,
    /** [NO_VALUE] when the sensor never delivered. */
    val avgHr: String,
    /** "Data · 1 sensor drop · 12 s gap · 0 crashes". */
    val dataQuality: String,
    /** "Your run" / "Your walk": the edits block's title. */
    val yourLabel: String,
    /** The tag chips: a run only (R0); a walk stores no tag. */
    val showsTags: Boolean,
    /** Preselected chip (D76); null for a walk or when nothing fits. */
    val suggestedTag: RunTag?,
) {
    companion object {
        fun from(summary: RunSummary): SummaryModel {
            val recording = summary.recording
            val q = summary.quality
            return SummaryModel(
                typeLabel = noun(recording.type).replaceFirstChar { it.uppercase() },
                dateLine = "${RunFormat.date(recording.startUtc, recording.tzOffset)} · ${RunFormat.localTime(recording.startUtc, recording.tzOffset)}",
                distance = RunFormat.distance(summary.distance),
                totalTime = RunFormat.time(summary.totalTime),
                movingLine = "moving ${RunFormat.time(summary.activeTime)}",
                avgPace = if (summary.avgPace == null) NO_VALUE else RunFormat.pace(summary.avgPace),
                avgHr = summary.hrAvg?.toString() ?: NO_VALUE,
                dataQuality = "Data · ${plural(q.sensorDrops, "sensor drop")} · ${q.hrGap.inWholeSeconds} s gap · ${plural(q.crashes, "crash", "crashes")}",
                yourLabel = "Your ${noun(recording.type)}",
                showsTags = recording.type == RecordingType.Run,
                suggestedTag = summary.suggestedTag,
            )
        }

        fun noun(type: RecordingType) = when (type) { RecordingType.Run -> "run"; RecordingType.Walk -> "walk" }

        private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"
    }
}
