package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.inSecondsDouble
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
     * Seconds per km over [metres] covered in active [time]; null under [paceMinDistance] (D102). The ONE
     * pace rule: the live split pace, the summary's average pace and each split row read it.
     */
    fun pace(time: Duration, metres: Double): Double? =
        if (metres >= paceMinDistance) time.inSecondsDouble / metres * distance else null

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
     * @param steps [Elevation.steps] of [fixes] over [pauses]; pass it when the caller already has it.
     */
    fun table(
        marks: List<Mark>,
        startedAt: Instant,
        stoppedAt: Instant,
        totalDistance: Double,
        pauses: List<Pause>,
        hr: List<HrSample>,
        fixes: List<LocationFix>,
        steps: List<Elevation.Step> = Elevation.steps(fixes, pauses),
    ): List<Row> {
        val rows = ArrayList<Row>()
        val cursor = Cursor(hr, steps)
        var start = startedAt
        for (mark in marks) {
            rows += cursor.row(mark.index, start, mark.at, distance, pauses, partial = false)
            start = mark.at
        }
        val remainder = totalDistance - marks.size * distance
        if (remainder > 0 && stoppedAt > start) rows += cursor.row(marks.size + 1, start, stoppedAt, remainder, pauses, partial = true)
        return rows
    }

    /**
     * The rows are consecutive `[start, end)` windows and both streams are in time order, so one forward
     * pass reads them: each window starts where the last one stopped (two-pointer walk, O(rows + samples)).
     */
    private class Cursor(private val hr: List<HrSample>, private val steps: List<Elevation.Step>) {
        private var h = 0
        private var s = 0

        fun row(index: Int, start: Instant, end: Instant, metres: Double, pauses: List<Pause>, partial: Boolean): Row {
            while (h < hr.size && hr[h].t < start) h++
            var sum = 0
            var count = 0
            while (h < hr.size && hr[h].t < end) {
                val bpm = hr[h++].bpm
                if (HeartRate.isValid(bpm)) { sum += bpm; count++ }
            }
            while (s < steps.size && steps[s].at < start) s++
            val from = s
            while (s < steps.size && steps[s].at < end) s++
            val climb = Elevation.totals(steps.subList(from, s))
            val time = Pauses.activeBetween(pauses, start, end)
            return Row(
                index = index, startAt = start, endAt = end, distance = metres, time = time,
                pace = pace(time, metres),
                avgHr = HeartRate.mean(sum, count),
                ascent = climb.ascent, descent = climb.descent, partial = partial,
            )
        }
    }
}
