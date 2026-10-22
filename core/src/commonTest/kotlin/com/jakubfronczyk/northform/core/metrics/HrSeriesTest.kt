package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** summary-screen/03: the heart-rate line the Summary draws, 5 s buckets over the effort with its sensor gaps. */
class HrSeriesTest {
    private val start = Instant.fromEpochSeconds(1_000_000)
    private val stop = start + 60.seconds
    private fun at(s: Int) = start + s.seconds

    @Test
    fun samples_are_averaged_into_five_second_buckets_from_the_start() {
        val samples = (0..59).map { HrSample(at(it), 140 + it) } // 140, 141, …
        val series = HrSeries.compute(samples, start, stop)
        assertEquals(12, series.points.size)
        assertEquals(HrSeries.Point(0.seconds, 142), series.points.first(), "mean of 140..144")
        assertEquals(HrSeries.Point(55.seconds, 197), series.points.last(), "mean of 195..199")
        assertEquals(emptyList(), series.gaps)
    }

    @Test
    fun a_stretch_without_samples_as_long_as_the_hr_lost_threshold_is_a_gap_a_shorter_one_is_not() {
        val samples = (0..59).filter { it !in 20..25 && it != 40 }.map { HrSample(at(it), 150) } // 19 → 26 is 7 s; 39 → 41 is 2 s
        val series = HrSeries.compute(samples, start, stop)
        assertEquals(listOf(HrSeries.Gap(19.seconds, 26.seconds)), series.gaps)
        assertEquals(7.seconds, series.gaps.single().length)
        assertEquals(listOf(0, 5, 10, 15, 25, 30, 35, 40, 45, 50, 55).map { it.seconds }, series.points.map { it.at }, "the 20 s bucket is empty")
    }

    @Test
    fun the_edges_count_too_and_the_cool_down_is_left_out() {
        val samples = (8..52).map { HrSample(at(it), 150) } + (61..120).map { HrSample(at(it), 120) }
        val series = HrSeries.compute(samples, start, stop)
        assertEquals(listOf(HrSeries.Gap(0.seconds, 8.seconds), HrSeries.Gap(52.seconds, 60.seconds)), series.gaps, "no sample at the start, none before the stop")
        assertEquals(50.seconds, series.points.last().at, "nothing after the stop")
    }

    @Test
    fun invalid_samples_are_ignored_like_everywhere_else() {
        val samples = (0..59).map { HrSample(at(it), if (it in 10..19) 0 else 150) }
        val series = HrSeries.compute(samples, start, stop)
        assertEquals(listOf(HrSeries.Gap(9.seconds, 20.seconds)), series.gaps)
    }
}
