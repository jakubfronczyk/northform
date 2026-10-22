package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Splits
import com.jakubfronczyk.northform.core.metrics.Zone
import com.jakubfronczyk.northform.core.metrics.all
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunSummary
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.jakubfronczyk.northform.ui.RunFormat

/**
 * Everything the SummaryDark artboard shows, as plain values: one pure function of the
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
    /** SU6: "New max heart rate: 191" / "Higher than your 187. Update zones?"; null hides the card. */
    val hrMaxSuggestion: String?,
    val hrMaxCurrent: String,
    /** The map's line (the platform map slot draws it). */
    val route: Route,
    // ── The "Heart rate" card (H1, H3, H4, H7) ──
    val hrAverage: String,
    val hrMax: String,
    /** "−31": the change a minute after the stop (the artboard's sign; H7's metric stores stop − later = +31). [NO_VALUE] when not measured. */
    val recovery: String,
    /** "172 → 141" under the number, or "skipped". */
    val recoveryDetail: String,
    /** The line: x = seconds from the start over [effortSeconds], y = bpm; broken at [gapLabels]' gaps. */
    val linePoints: List<LinePoint>,
    val effortSeconds: Double,
    /** The six zone bands behind the line, bpm bounds from the profile; Z0 and Z5 open-ended to [bpmRange]. */
    val bands: List<Band>,
    val bpmRange: IntRange,
    /** The sensor gaps as the line shows them; [GapLabel.text] only from 10 s ("sensor gap 12 s"), empty under. */
    val gapLabels: List<GapLabel>,
    /** "Total per zone": Z0 → Z5, bar length as a fraction of the longest. */
    val zones: List<ZoneRow>,
    // ── Splits (R6, R7) ──
    val splits: List<SplitRow>,
) {
    data class LinePoint(val seconds: Double, val bpm: Int)
    data class Band(val zone: Int, val lowBpm: Int, val highBpm: Int)
    data class GapLabel(val fromSeconds: Double, val untilSeconds: Double, val text: String)
    data class ZoneRow(val label: String, val name: String, val time: String, val fraction: Float, val zone: Int)
    /** One km; [paceFraction] is the pace bar (the fastest km fills it); [elev] "+12 / −4" m. */
    data class SplitRow(val km: String, val pace: String, val paceFraction: Float, val hr: String, val elev: String, val partial: Boolean)

    companion object {
        fun from(summary: RunSummary): SummaryModel {
            val recording = summary.recording
            val q = summary.quality
            val profile = summary.profile
            val bpmRange = bpmRange(summary)
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
                hrMaxSuggestion = summary.hrMaxSuggestion?.toString(),
                hrMaxCurrent = profile.hrMax.toString(),
                route = summary.route,
                hrAverage = summary.hrAvg?.toString() ?: NO_VALUE,
                hrMax = summary.hrMax?.toString() ?: NO_VALUE,
                recovery = summary.hrRecovery?.let { signed(-it) } ?: NO_VALUE,
                recoveryDetail = summary.hrRecoveryReadings?.let { "${it.atStop} → ${it.later}" } ?: "skipped",
                linePoints = summary.hrSeries.points.map { LinePoint(it.at.inSecondsDouble, it.bpm) },
                effortSeconds = summary.totalTime.inSecondsDouble,
                bands = bands(profile, bpmRange),
                bpmRange = bpmRange,
                gapLabels = summary.hrSeries.gaps.map { g ->
                    GapLabel(g.from.inSecondsDouble, g.until.inSecondsDouble, if (g.length >= 10.seconds) "sensor gap ${g.length.inWholeSeconds} s" else "")
                },
                zones = zoneRows(summary.timeInZone),
                splits = splitRows(summary.splits),
            )
        }

        /** The chart's y-range: the profile's rest to max, widened to any sample outside it. */
        private fun bpmRange(summary: RunSummary): IntRange {
            val profile = summary.profile
            val seen = summary.hrSeries.points.map { it.bpm }
            return minOf(profile.hrRest, seen.minOrNull() ?: profile.hrRest)..maxOf(profile.hrMax, seen.maxOrNull() ?: profile.hrMax)
        }

        private fun bands(profile: Profile, range: IntRange): List<Band> = profile.zones.all.map { zone ->
            Band(zone.number, lowBpm = zone.lowerFraction?.let { profile.bpmAt(it) } ?: range.first, highBpm = zone.upperFraction?.let { profile.bpmAt(it) } ?: range.last)
        }

        private fun Profile.bpmAt(reserveFraction: Double): Int = (hrRest + reserveFraction * (hrMax - hrRest)).roundedHalfAwayFromZero().toInt()

        private fun zoneRows(timeInZone: Map<Zone, Duration>): List<ZoneRow> {
            val longest = timeInZone.values.maxOrNull()?.takeIf { it > Duration.ZERO }
            return timeInZone.entries.sortedBy { it.key.number }.map { (zone, time) ->
                ZoneRow(zone.label, zone.name, RunFormat.time(time), longest?.let { (time / it).toFloat() } ?: 0f, zone.number)
            }
        }

        private fun splitRows(rows: List<Splits.Row>): List<SplitRow> {
            val fastest = rows.mapNotNull { it.pace }.minOrNull()
            return rows.map { row ->
                val pace = row.pace
                SplitRow(
                    km = if (row.partial) RunFormat.distance(row.distance).trimEnd('0').trimEnd('.') else row.index.toString(),
                    pace = if (pace == null) NO_VALUE else RunFormat.pace(pace),
                    paceFraction = if (pace == null || fastest == null) 0f else (fastest / pace).toFloat(),
                    hr = row.avgHr?.toString() ?: NO_VALUE,
                    elev = "${signed(row.ascent.roundedHalfAwayFromZero().toInt())} / ${signed(-row.descent.roundedHalfAwayFromZero().toInt())}",
                    partial = row.partial,
                )
            }
        }

        private fun signed(n: Int) = if (n < 0) "−${-n}" else "+$n"

        fun noun(type: RecordingType) = when (type) { RecordingType.Run -> "run"; RecordingType.Walk -> "walk" }

        private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"
    }
}
