package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.MetricId
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant

/**
 * The summary metrics of one recording as stored rows (`MetricResult`, `metric` table): each value
 * with its algorithm version and the profile it was computed with, so a later profile edit never
 * changes history (E15) and "recompute on demand" is this function called again with the new
 * profile (SU6 accept, D24). A metric that cannot be computed is left out, not stored as NaN.
 *
 * Pure: the caller (engine/04 summary computing) reads the store and passes the streams in.
 */
object RecordingMetrics {
    /** Every id this catalogue writes, with the version of its formula. Bump a version when a formula changes. */
    object Ids {
        /** Seconds in zone n, `time_in_zone.z0` … `z5`. */
        fun timeInZone(zone: Int) = MetricId("time_in_zone.z$zone")
        val trimp = MetricId("trimp")
        val sRpe = MetricId("srpe")
        /** bpm at stop − bpm 60 s later. */
        val hrRecovery = MetricId("hr_recovery")
        // The [Headline] rows (today-screen spec).
        /** Metres. */
        val distance = MetricId("distance")
        /** Seconds, pauses included. */
        val totalTime = MetricId("total_time")
        /** Seconds, pauses excluded. */
        val movingTime = MetricId("moving_time")
        /** bpm. */
        val avgHr = MetricId("avg_hr")
    }

    const val version = 1

    data class Input(
        /** The recording's heart-rate samples in time order, cool-down included. */
        val samples: List<HrSample>,
        /** Hold-to-stop (`Recording.endUtc`); metrics of the effort read samples up to here. */
        val stoppedAt: Instant,
        /** The `CooldownEnd` event, if it happened. */
        val cooldownEndedAt: Instant?,
        /** Total − paused (spec 08 "Active time"), for sRPE. */
        val activeTime: Duration,
        /** 0–10 when the user rated the session. */
        val rpe: Int?,
        /** D32: HR recovery is a run/walk metric; false for anything else. */
        val recoveryApplies: Boolean = true,
    )

    fun compute(input: Input, profile: Profile): List<MetricResult> {
        val effort = input.samples.filter { it.t <= input.stoppedAt }
        return compute(input, profile, TimeInZone.compute(effort, profile), Trimp.compute(effort, profile))
    }

    /**
     * [compute] with H3 and H5 already computed by the caller (the summary shows them too), so the HR
     * stream is not walked again. Both must be of `input.samples` up to `input.stoppedAt`, with [profile].
     */
    fun compute(input: Input, profile: Profile, timeInZone: Map<Zone, Duration>, trimp: Double): List<MetricResult> {
        fun result(id: MetricId, value: Double) = MetricResult(id, version, value, profile)
        val out = ArrayList<MetricResult>()
        for ((zone, time) in timeInZone) out += result(Ids.timeInZone(zone.number), time.toDouble(DurationUnit.SECONDS))
        out += result(Ids.trimp, trimp)
        input.rpe?.let { out += result(Ids.sRpe, Trimp.sRpe(it, input.activeTime)) }
        if (input.recoveryApplies) {
            HrRecovery.compute(input.samples, input.stoppedAt, input.cooldownEndedAt)?.let { out += result(Ids.hrRecovery, it.toDouble()) }
        }
        return out
    }
}
