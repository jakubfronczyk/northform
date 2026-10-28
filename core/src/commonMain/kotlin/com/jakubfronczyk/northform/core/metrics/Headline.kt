package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.MetricId
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.inSecondsDouble
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The numbers a saved recording is listed by (today-screen spec): stored as metric rows with Save, read back by
 * Today and History without decoding the streams. Average pace is derived, never stored.
 */
data class Headline(
    /** Metres counted (D111). */
    val distance: Double,
    /** Stop − start, pauses included (D28). */
    val totalTime: Duration,
    /** Total − paused. */
    val movingTime: Duration,
    /** H1 over the effort; null when the sensor never delivered. */
    val avgHr: Int?,
) {
    /** Seconds per km over [movingTime], the Summary's rule; null under `Splits.paceMinDistance`. */
    val avgPace: Double? get() = Splits.pace(movingTime, distance)

    /** The rows Save stores (each with [profile], E15); no `avg_hr` row without heart rate. */
    fun metrics(profile: Profile): List<MetricResult> {
        fun result(id: MetricId, value: Double) = MetricResult(id, RecordingMetrics.version, value, profile)
        return listOfNotNull(
            result(RecordingMetrics.Ids.distance, distance),
            result(RecordingMetrics.Ids.totalTime, totalTime.inSecondsDouble),
            result(RecordingMetrics.Ids.movingTime, movingTime.inSecondsDouble),
            avgHr?.let { result(RecordingMetrics.Ids.avgHr, it.toDouble()) },
        )
    }

    companion object {
        /** The inverse of [metrics]; null when a recording's rows lack any of the three that are always written. */
        fun of(metrics: List<MetricResult>): Headline? {
            val byId = metrics.associate { it.metricId to it.value }
            return Headline(
                distance = byId[RecordingMetrics.Ids.distance] ?: return null,
                totalTime = (byId[RecordingMetrics.Ids.totalTime] ?: return null).seconds,
                movingTime = (byId[RecordingMetrics.Ids.movingTime] ?: return null).seconds,
                avgHr = byId[RecordingMetrics.Ids.avgHr]?.toInt(),
            )
        }
    }
}
