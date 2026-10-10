package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TrimpTest {
    // reserve 127: 150 bpm = ΔHR 90/127 = 0.70866…
    private val male = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60)
    private val female = male.copy(sex = Sex.Female)
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun hr(s: Double, bpm: Int) = HrSample(t0 + s.seconds, bpm)

    /** A 1 Hz stream: each (bpm, seconds) segment held that long, one closing sample at the end. */
    private fun stream(vararg segments: Pair<Int, Int>, from: Double = 0.0): List<HrSample> {
        val out = ArrayList<HrSample>()
        var s = from
        for ((bpm, seconds) in segments) repeat(seconds) { out += hr(s, bpm); s += 1 }
        out += hr(s, segments.last().first)
        return out
    }

    private fun assertClose(expected: Double, actual: Double, message: String) =
        assertTrue(abs(expected - actual) < 1e-9, "$message: expected $expected, was $actual")

    /** One held minute at 150 bpm, by hand, for both constant sets (spec 08: male k 0.64 b 1.92 · female k 0.86 b 1.67). */
    @Test
    fun one_minute_at_a_steady_heart_rate_matches_the_formula_for_each_sex() {
        val samples = stream(150 to 60)
        val deltaHr = 90.0 / 127.0
        val cases = listOf(
            male to 1.0 * deltaHr * 0.64 * exp(1.92 * deltaHr),
            female to 1.0 * deltaHr * 0.86 * exp(1.67 * deltaHr),
        )
        for ((profile, expected) in cases) assertClose(expected, Trimp.compute(samples, profile), profile.sex.name)
    }

    @Test
    fun the_load_adds_interval_by_interval_with_the_earlier_sample_held() {
        // 30 s at 150 (ΔHR 90/127), then 30 s at 170 (ΔHR 110/127); the last sample only closes the interval
        val samples = stream(150 to 30, 170 to 30)
        val d1 = 90.0 / 127.0
        val d2 = 110.0 / 127.0
        val expected = 0.5 * d1 * 0.64 * exp(1.92 * d1) + 0.5 * d2 * 0.64 * exp(1.92 * d2)
        assertClose(expected, Trimp.compute(samples, male), "two intervals")
    }

    @Test
    fun below_resting_heart_rate_adds_nothing_instead_of_a_negative_load() {
        val cases = listOf(
            "all below rest" to stream(50 to 30, 55 to 30),
            "at rest exactly" to stream(60 to 60),
        )
        for ((name, samples) in cases) assertEquals(0.0, Trimp.compute(samples, male), name)
        // a mixed stream: the sub-rest minute adds 0, the 150 bpm minute adds its full share
        val mixed = stream(50 to 60, 150 to 60)
        assertClose(Trimp.compute(stream(150 to 60), male), Trimp.compute(mixed, male), "mixed")
    }

    @Test
    fun an_hr_gap_and_invalid_samples_are_skipped() {
        val steady = stream(150 to 60)
        val withGap = stream(150 to 60) + stream(150 to 60, from = 200.0) // 140 s without samples in the middle
        assertClose(2 * Trimp.compute(steady, male), Trimp.compute(withGap, male), "the gap contributes nothing")
        val withInvalid = listOf(hr(0.0, 150), hr(3.0, 250), hr(4.0, 20), hr(6.0, 150)) // the invalid pair is dropped: 0 → 6 is one 6 s span, over the gap
        assertEquals(0.0, Trimp.compute(withInvalid, male), "a span bridged only by invalid samples is a gap")
        val invalidInside = listOf(hr(0.0, 150), hr(1.0, 250), hr(2.0, 150))
        assertClose(Trimp.compute(listOf(hr(0.0, 150), hr(2.0, 150)), male), Trimp.compute(invalidInside, male), "an invalid sample inside a short span is simply dropped")
    }

    @Test
    fun no_samples_means_no_load() {
        assertEquals(0.0, Trimp.compute(emptyList(), male))
        assertEquals(0.0, Trimp.compute(listOf(hr(0.0, 150)), male))
    }

    @Test
    fun session_rpe_is_rpe_times_active_minutes() {
        val cases = listOf(
            Triple(0, 40.minutes, 0.0),
            Triple(5, 40.minutes, 200.0),
            Triple(10, 90.seconds, 15.0),
        )
        for ((rpe, active, expected) in cases) assertEquals(expected, Trimp.sRpe(rpe, active), "rpe $rpe × $active")
        assertFailsWith<IllegalArgumentException> { Trimp.sRpe(11, 10.minutes) }
    }
}
