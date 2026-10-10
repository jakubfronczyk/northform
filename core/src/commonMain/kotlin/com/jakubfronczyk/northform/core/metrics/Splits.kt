package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * R6 — per-km splits (D22 auto-lap), live and in the summary from ONE rule.
 *
 * Live, the reducer counts GPS distance (`DistanceTracker`, D111) and when a counted segment
 * crosses a km mark it asks [markReachedAt] for the moment the mark was reached (D121) and records
 * a `Split` event there. The summary does not walk the segments again: its marks ARE those stored
 * events, so "summary splits == live splits" holds by construction and there is no second copy of
 * the interpolation. What the summary adds per row is read from the other streams: active time
 * from the pauses ([Pauses]), average heart rate from the samples, climb from the altitude
 * ([Elevation]).
 */
object Splits {
    /** D22: a split every kilometre. */
    const val distance = 1000.0

    /** D102: a split pace means something only from here on. */
    const val paceMinDistance = 50.0

    /**
     * D121, the shared interpolation (`RunReducer.swift:249`): a counted segment of [metres] from
     * [from] to [to] carried the run from [before] metres; the km [mark] inside it was reached at the
     * moment linear in time along the segment.
     */
    fun markReachedAt(mark: Double, before: Double, metres: Double, from: Instant, to: Instant): Instant =
        from + (to - from) * ((mark - before) / metres)

    /** One km mark as the run recorded it: `index` starts at 1. */
    data class Mark(val index: Int, val at: Instant)

    /** The stored `Split` events, in order. */
    fun marks(events: List<RecordingEvent>): List<Mark> =
        events.mapNotNull { event -> (event.kind as? EventKind.Split)?.let { Mark(it.index, event.t) } }

    /** One row of the splits table (R6). */
    data class Row(
        val index: Int,
        /** `[startAt, endAt)`: the previous mark (or the start) to this mark (or the stop). */
        val startAt: Instant,
        val endAt: Instant,
        /** Metres: [Splits.distance] for a full km, what was left for the partial last one. */
        val distance: Double,
        /** Wall time of the km minus the paused time inside it (spec 08 "Split"). */
        val time: Duration,
        /** Seconds per km; null under [paceMinDistance]. */
        val pace: Double?,
        /** Mean of the valid samples inside the row (H1's average), null when there were none. */
        val avgHr: Int?,
        val ascent: Double,
        val descent: Double,
        /** The last row when the run ended between two marks. */
        val partial: Boolean,
    )

    /**
     * @param marks the stored `Split` events ([marks]).
     * @param startedAt the run's start (`Recording.startUtc`); [stoppedAt] hold-to-stop (`Recording.endUtc`).
     * @param totalDistance the run's counted distance in metres (the live `distance`, or the rebuilt one: the same number, D111).
     * @param pauses [Pauses.intervals] of the recording's events.
     * @param hr the recording's heart-rate samples; [fixes] its GPS fixes (both in time order).
     */
    fun table(
        marks: List<Mark>,
        startedAt: Instant,
        stoppedAt: Instant,
        totalDistance: Double,
        pauses: List<Pause>,
        hr: List<HrSample>,
        fixes: List<LocationFix>,
    ): List<Row> {
        val steps = Elevation.steps(fixes, pauses)
        val rows = ArrayList<Row>()
        var start = startedAt
        for (mark in marks) {
            rows += row(mark.index, start, mark.at, distance, pauses, hr, steps, partial = false)
            start = mark.at
        }
        val remainder = totalDistance - marks.size * distance
        if (remainder > 0 && stoppedAt > start) rows += row(marks.size + 1, start, stoppedAt, remainder, pauses, hr, steps, partial = true)
        return rows
    }

    private fun row(
        index: Int, start: Instant, end: Instant, metres: Double,
        pauses: List<Pause>, hr: List<HrSample>, steps: List<Elevation.Step>, partial: Boolean,
    ): Row {
        val time = Pauses.activeBetween(pauses, start, end)
        val samples = hr.filter { it.t >= start && it.t < end && HeartRate.isValid(it.bpm) }
        val climb = Elevation.totals(steps.filter { it.at >= start && it.at < end })
        return Row(
            index = index, startAt = start, endAt = end, distance = metres, time = time,
            pace = if (metres >= paceMinDistance) time.inSecondsDouble / metres * distance else null,
            avgHr = if (samples.isEmpty()) null else (samples.sumOf { it.bpm }.toDouble() / samples.size).roundedHalfAwayFromZero().toInt(),
            ascent = climb.ascent, descent = climb.descent, partial = partial,
        )
    }
}
