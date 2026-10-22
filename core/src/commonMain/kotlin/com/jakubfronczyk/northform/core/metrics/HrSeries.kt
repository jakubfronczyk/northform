package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * H4 — the heart-rate line the Summary draws over the zone bands: the effort's samples averaged into
 * [bucket]-long buckets from the start, and the **sensor gaps** (GLOSSARY) where the line breaks. A gap
 * is a stretch with no valid sample at least [HrStream.gap] long, the reducer's own HR-lost rule, so the
 * line breaks exactly where the data-quality line counted lost time. Offsets are from the start, so the
 * chart needs no clock.
 */
data class HrSeries(val bucket: Duration, val points: List<Point>, val gaps: List<Gap>) {
    /** The mean of the valid samples in `[at, at + bucket)`; a bucket with none has no point. */
    data class Point(val at: Duration, val bpm: Int)

    /** `[from, until)` since the start; the last sample before and the first after bound it, the start and the stop at the edges. */
    data class Gap(val from: Duration, val until: Duration) {
        val length: Duration get() = until - from
    }

    companion object {
        val bucket: Duration = 5.seconds

        /**
         * @param samples the recording's samples in time order, cool-down included (only those in `[startedAt, stoppedAt]` count).
         * @param startedAt the recording's start; [stoppedAt] hold-to-stop.
         */
        fun compute(samples: List<HrSample>, startedAt: Instant, stoppedAt: Instant, bucket: Duration = HrSeries.bucket, gap: Duration = HrStream.gap): HrSeries {
            val effort = samples.filter { it.t >= startedAt && it.t <= stoppedAt && HeartRate.isValid(it.bpm) }
            val points = effort.groupBy { ((it.t - startedAt) / bucket).toLong() }
                .entries.sortedBy { it.key }
                .map { (index, inBucket) -> Point(bucket * index.toInt(), HeartRate.mean(inBucket) ?: error("a bucket with samples has a mean")) }
            val gaps = buildList {
                var last = startedAt
                for (sample in effort) {
                    if (sample.t - last >= gap) add(Gap(last - startedAt, sample.t - startedAt))
                    last = sample.t
                }
                if (stoppedAt - last >= gap) add(Gap(last - startedAt, stoppedAt - startedAt))
            }
            return HrSeries(bucket, points, gaps)
        }
    }
}
