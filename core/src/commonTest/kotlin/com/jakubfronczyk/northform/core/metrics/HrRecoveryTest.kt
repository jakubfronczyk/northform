package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HrRecoveryTest {
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private val stop = t0 + 600.seconds
    private fun hr(s: Double, bpm: Int) = HrSample(t0 + s.seconds, bpm)

    /** 1 Hz: 160 bpm up to the stop, then falling 1 bpm/s through the cool-down (to 100 at +60 s). */
    private fun coolDown(lastSecond: Int = 660, dropAfter: ClosedRange<Int>? = null): List<HrSample> =
        (590..lastSecond).filter { dropAfter == null || it !in dropAfter }.map { s -> hr(s.toDouble(), if (s <= 600) 160 else 160 - (s - 600)) }

    @Test
    fun a_full_cool_down_gives_hr_at_stop_minus_hr_sixty_seconds_later() {
        assertEquals(60, HrRecovery.compute(coolDown(), stop, cooldownEndedAt = stop + 60.seconds))
    }

    @Test
    fun a_skipped_cool_down_has_no_value_even_with_samples_near_the_mark() {
        val cases = listOf(
            "skipped at 58 s" to (stop + 58.seconds),
            "skipped at 10 s" to (stop + 10.seconds),
            "still running" to null,
        )
        for ((name, endedAt) in cases) assertEquals(null, HrRecovery.compute(coolDown(), stop, endedAt), name)
    }

    @Test
    fun hr_lost_during_the_cool_down_gives_no_value_when_the_sixty_second_reading_is_missing() {
        val cases = listOf(
            "no sample from 55 s on" to coolDown(dropAfter = 655..660),
            "no sample in the last 5 s" to coolDown(dropAfter = 656..660), // 655 is exactly 5 s back: outside the window
        )
        for ((name, samples) in cases) assertEquals(null, HrRecovery.compute(samples, stop, stop + 60.seconds), name)
        // a loss that ended before the mark is fine: the reading at 60 s exists
        assertEquals(60, HrRecovery.compute(coolDown(dropAfter = 620..650), stop, stop + 60.seconds), "lost 20–50 s, back by 60 s")
        // the last sample a little before the mark still counts (tolerance 5 s): 659 → 101 bpm
        assertEquals(59, HrRecovery.compute(coolDown(dropAfter = 660..660), stop, stop + 60.seconds), "last sample at 59 s")
    }

    @Test
    fun hr_lost_at_the_stop_gives_no_value() {
        assertEquals(null, HrRecovery.compute(coolDown(dropAfter = 594..600), stop, stop + 60.seconds), "no sample in the 5 s before stop")
        assertEquals(60, HrRecovery.compute(coolDown(dropAfter = 594..599), stop, stop + 60.seconds), "the sample at the stop moment is enough")
    }

    @Test
    fun invalid_samples_are_never_a_reading() {
        val samples = coolDown(dropAfter = 656..660) + listOf(hr(660.0, 250))
        assertEquals(null, HrRecovery.compute(samples, stop, stop + 60.seconds), "250 bpm at 60 s is not a reading")
        val withSpikeAtStop = coolDown().map { if (it.t == stop) HrSample(it.t, 0) else it }
        assertEquals(60, HrRecovery.compute(withSpikeAtStop, stop, stop + 60.seconds), "0 bpm at the stop is skipped, 599 s (160) read instead")
    }

    @Test
    fun a_longer_cool_down_window_is_honoured() {
        assertEquals(30, HrRecovery.compute(coolDown(), stop, stop + 30.seconds, cooldown = 30.seconds))
    }
}

/** summary-screen/03: the two readings behind the number ("172 → 141"). */
class HrRecoveryReadingsTest {
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private val stop = t0 + 600.seconds
    private fun coolDown() = (590..660).map { s -> HrSample(t0 + s.seconds, if (s <= 600) 160 else 160 - (s - 600)) }

    @Test
    fun readings_are_the_bpm_at_stop_and_sixty_seconds_later() {
        assertEquals(HrRecovery.Readings(atStop = 160, later = 100), HrRecovery.readings(coolDown(), stop, cooldownEndedAt = stop + 60.seconds))
        assertEquals(60, HrRecovery.compute(coolDown(), stop, cooldownEndedAt = stop + 60.seconds), "compute is their difference")
    }

    @Test
    fun a_skipped_cool_down_has_no_readings() {
        assertEquals(null, HrRecovery.readings(coolDown(), stop, cooldownEndedAt = stop + 10.seconds))
    }
}
