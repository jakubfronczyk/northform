package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Elevation
import com.jakubfronczyk.northform.core.metrics.HeartRate
import com.jakubfronczyk.northform.core.metrics.HrMaxSuggestion
import com.jakubfronczyk.northform.core.metrics.HrRecovery
import com.jakubfronczyk.northform.core.metrics.HrSeries
import com.jakubfronczyk.northform.core.metrics.Pause
import com.jakubfronczyk.northform.core.metrics.Pauses
import com.jakubfronczyk.northform.core.metrics.RecordingMetrics
import com.jakubfronczyk.northform.core.metrics.RunTagSuggestion
import com.jakubfronczyk.northform.core.metrics.Splits
import com.jakubfronczyk.northform.core.metrics.TimeInZone
import com.jakubfronczyk.northform.core.metrics.Trimp
import com.jakubfronczyk.northform.core.metrics.Zone
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * R27 — what the Summary screen shows after the cool-down (engine/04), computed once from the stored
 * recording in the `computing` state. A pure value: `compute` reads [RecordingData] and the profile
 * (D24: the profile the run was computed with), nothing else, so the screen, a replay test and a later
 * "recompute with the new HRmax" (SU6) all get the same numbers from the same function.
 *
 * Every number comes from the stored streams, not the live snapshot: average and maximum heart rate
 * read samples up to the stop, so the cool-down never enters them (the live `hrAvg` kept counting
 * through it), and distance is the reducer's own count rebuilt from the fixes (D111), the number the
 * run screen showed.
 */
data class RunSummary(
    val recording: Recording,
    val profile: Profile,
    /** Hold-to-stop (`Recording.endUtc`). */
    val stoppedAt: Instant,
    /** Stop − start, pauses included: the main time (D28). */
    val totalTime: Duration,
    val pausedTime: Duration,
    /** Total − paused (spec 08). */
    val activeTime: Duration,
    /** Metres counted (D111). */
    val distance: Double,
    /** The line the run screen drew, replayed from the stored fixes (the Summary's map). */
    val route: Route,
    /**
     * Seconds per km over [activeTime]; null under `Splits.paceMinDistance`. A continued crash is a stored
     * pause (D40, `RunReducer.resumed`), so its gap is out of active time and out of distance alike.
     */
    val avgPace: Double?,
    /** H1 over the effort (samples ≤ stop, valid only); null when the sensor never delivered. */
    val hrAvg: Int?,
    val hrMax: Int?,
    /** H3, every zone a key (Z0 → Z5). */
    val timeInZone: Map<Zone, Duration>,
    /** H5. */
    val trimp: Double,
    /** H7: bpm at stop − bpm 60 s later; null when skipped or not measured. */
    val hrRecovery: Int?,
    /** The two readings behind [hrRecovery] ("172 → 141"); null exactly when it is. */
    val hrRecoveryReadings: HrRecovery.Readings?,
    /** H4: the line the Summary draws, 5 s buckets over the effort with its sensor gaps (summary-screen/03). */
    val hrSeries: HrSeries,
    /** R6. */
    val splits: List<Splits.Row>,
    /** R7 (D25). */
    val ascent: Double,
    val descent: Double,
    /** R0 / D76; null for a walk. */
    val suggestedTag: RunTag?,
    /** SU6: a higher HRmax seen for 10 s, to offer; null when none. */
    val hrMaxSuggestion: Int?,
    val quality: DataQuality,
    /** The samples the catalogue runs on (cool-down included), kept so [metrics] needs nothing else ([timeInZone] and [trimp] are reused). */
    private val samples: List<HrSample>,
    private val cooldownEndedAt: Instant?,
) {
    /** The "Data ·" line: how much the run can be trusted. */
    data class DataQuality(
        /** `SensorLost` events: Bluetooth drops. */
        val sensorDrops: Int,
        /** Σ `HrLost` → `HrBack` (or stop): time with no heart rate. */
        val hrGap: Duration,
        /** `GpsLost` events. */
        val gpsLosses: Int,
        /** `CrashRecovered` events: every crash the run went through (D32). */
        val crashes: Int,
    )

    /** The metric rows to store with Save (E15: each carries [profile]); `rpe` adds the sRPE row. */
    fun metrics(rpe: Int?): List<MetricResult> = RecordingMetrics.compute(
        RecordingMetrics.Input(
            samples = samples, stoppedAt = stoppedAt, cooldownEndedAt = cooldownEndedAt, activeTime = activeTime, rpe = rpe,
            recoveryApplies = recording.type == RecordingType.Run || recording.type == RecordingType.Walk, // D32
        ),
        profile, timeInZone, trimp,
    )

    companion object {
        /**
         * @param data the recording as the store holds it after the cool-down; `recording.endUtc` must be set (stopped).
         * @param profile the profile to compute with: the current one, or the one stored with earlier metrics (D24).
         */
        fun compute(data: RecordingData, profile: Profile): RunSummary {
            val recording = data.recording
            val startedAt = recording.startUtc
            val stoppedAt = requireNotNull(recording.endUtc) { "summary of a recording that was not stopped: ${recording.id.value}" }
            val pauses: List<Pause> = Pauses.intervals(data.events, stoppedAt)
            val totalTime = stoppedAt - startedAt
            val pausedTime = Pauses.pausedWithin(pauses, startedAt, stoppedAt)
            val activeTime = totalTime - pausedTime
            // D111: the same tracker the live run counted with, replayed over the stored fixes; the route comes with it.
            val (distance, route) = RunReducer.replayed(data)
            val effort = data.hr.filter { it.t <= stoppedAt && HeartRate.isValid(it.bpm) }
            val cooldownEndedAt = data.events.firstOrNull { it.kind == EventKind.CooldownEnd }?.t
            // One altitude pass for the run and its rows: steps depend only on earlier fixes, so the
            // steps up to the stop are the steps of the fixes up to the stop.
            val steps = Elevation.steps(data.fixes, pauses)
            val climb = Elevation.totals(steps.filter { it.at <= stoppedAt })
            val timeInZone = TimeInZone.compute(effort, profile)
            val recovery = if (recording.type == RecordingType.Run || recording.type == RecordingType.Walk) HrRecovery.readings(data.hr, stoppedAt, cooldownEndedAt) else null
            return RunSummary(
                recording = recording, profile = profile, stoppedAt = stoppedAt,
                totalTime = totalTime, pausedTime = pausedTime, activeTime = activeTime,
                distance = distance, route = route,
                avgPace = Splits.pace(activeTime, distance),
                hrAvg = HeartRate.mean(effort),
                hrMax = effort.maxOfOrNull { it.bpm },
                timeInZone = timeInZone,
                trimp = Trimp.compute(effort, profile),
                hrRecovery = recovery?.let { it.atStop - it.later },
                hrRecoveryReadings = recovery,
                hrSeries = HrSeries.compute(data.hr, startedAt, stoppedAt),
                splits = Splits.table(Splits.marks(data.events), startedAt, stoppedAt, distance, pauses, data.hr, data.fixes, steps),
                ascent = climb.ascent, descent = climb.descent,
                suggestedTag = RunTagSuggestion.suggest(recording.type, effort, profile, totalTime, distance, timeInZone),
                hrMaxSuggestion = HrMaxSuggestion.detect(effort, profile),
                quality = quality(data, stoppedAt),
                samples = data.hr, cooldownEndedAt = cooldownEndedAt,
            )
        }

        /** The reliability log's counts (R29), with an HR loss still open at the stop closed there. */
        private fun quality(data: RecordingData, stoppedAt: Instant): DataQuality {
            val log = ReliabilityRecord.of(data, closeAt = stoppedAt)
            return DataQuality(
                sensorDrops = log.drops,
                hrGap = log.hrLost,
                gpsLosses = data.events.count { it.kind == EventKind.GpsLost }, // a count; the log keeps GPS lost time
                crashes = log.crashes,
            )
        }
    }
}

/**
 * Summary → Save (R27): one call, state `computing` → `saved`, the metric rows of [RunSummary.metrics]
 * stored with the tag, RPE and notes the user chose. Delete is `store.delete(id)` as it was; the two
 * confirmations are the screen's (run-flow-ui/02).
 */
suspend fun RecordingStore.saveRunSummary(summary: RunSummary, tag: RunTag?, rpe: Int?, notes: String?) =
    saveSummary(summary.recording.id, tag, rpe, notes, summary.metrics(rpe))
