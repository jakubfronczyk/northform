package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ZoneTest {
    // reserve = 127: Z1 from 123.5 bpm, Z2 136.2, Z3 148.9, Z4 161.6, Z5 174.3
    private val profile = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60)
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun at(s: Double) = t0 + s.seconds
    private fun hr(s: Double, bpm: Int) = HrSample(at(s), bpm)

    @Test
    fun zone_of_a_bpm_at_every_boundary() {
        // (bpm, expected zone): one below, one at or above each lower bound
        val cases = listOf(
            59 to 0, 60 to 0, 123 to 0, 124 to 1, // 123.5 bpm is the Z1 bound
            136 to 1, 137 to 2, // 136.2
            148 to 2, 149 to 3, // 148.9
            161 to 3, 162 to 4, // 161.6
            174 to 4, 175 to 5, // 174.3
            187 to 5, 200 to 5,
        )
        for ((bpm, expected) in cases) {
            assertEquals(expected, profile.zoneOf(bpm).number, "$bpm bpm")
        }
    }

    @Test
    fun zone_of_a_fraction_at_the_exact_bounds() {
        val z = HRZones.standard
        val cases = listOf(-0.1 to 0, 0.0 to 0, 0.4999 to 0, 0.5 to 1, 0.6 to 2, 0.7 to 3, 0.8 to 4, 0.8999 to 4, 0.9 to 5, 1.2 to 5)
        for ((fraction, expected) in cases) {
            val zone = z.zoneOf(fraction)
            assertEquals(expected, zone.number, "fraction $fraction")
            assertEquals(true, fraction in zone, "fraction $fraction lies in its own zone's range")
        }
    }

    @Test
    fun zones_carry_number_name_and_range() {
        val all = HRZones.standard.all
        assertEquals(listOf(0, 1, 2, 3, 4, 5), all.map { it.number })
        assertEquals(listOf("Z0", "Z1", "Z2", "Z3", "Z4", "Z5"), all.map { it.label })
        assertEquals(Zone.names, all.map { it.name })
        assertEquals(listOf(null, 0.5, 0.6, 0.7, 0.8, 0.9), all.map { it.lowerFraction })
        assertEquals(listOf(0.5, 0.6, 0.7, 0.8, 0.9, null), all.map { it.upperFraction })
    }

    /** SU2: boundaries are %HRR, so a new HRmax moves the bpm bounds and keeps the fractions. */
    @Test
    fun an_hrmax_change_keeps_the_percent_boundaries() {
        val edited = HRZones(listOf(0.55, 0.65, 0.72, 0.82, 0.92))
        val before = profile.copy(zones = edited)
        val after = before.copy(hrMax = 195)
        assertEquals(before.zones.all, after.zones.all, "same zones, same %HRR ranges")
        // 70 % of reserve: 60 + 0.7·127 = 148.9 → Z2 (72 % bound) before; 60 + 0.7·135 = 154.5 → still Z2 after
        assertEquals(2, before.zoneOf(149).number)
        assertEquals(2, after.zoneOf(155).number)
        // and the same bpm lands lower once HRmax rises: 160 bpm is 78.7 % → Z3 before, 74 % → Z3 after, 170 bpm 86.6 % → Z4 before, 81.5 % → Z3 after
        assertEquals(4, before.zoneOf(170).number)
        assertEquals(3, after.zoneOf(170).number)
    }

    @Test
    fun held_intervals_skip_gaps_and_invalid_samples() {
        val samples = listOf(
            hr(0.0, 120), hr(1.0, 120), hr(2.0, 125), // two 1 s intervals
            hr(3.0, 0), hr(3.5, 250), // invalid: dropped, so 2.0 → 7.5 is one 5.5 s span, over the gap
            hr(7.5, 130), hr(12.5, 130), // exactly the 5 s gap: held
            hr(13.0, 130), // 0.5 s
        )
        val intervals = HrStream.intervals(samples)
        assertEquals(listOf(1.0, 1.0, 5.0, 0.5), intervals.map { it.duration.toDouble(kotlin.time.DurationUnit.SECONDS) })
        assertEquals(listOf(120, 120, 130, 130), intervals.map { it.bpm })
        assertEquals(7.5.seconds, HrStream.heldTime(samples))
    }

    @Test
    fun time_in_zone_attributes_each_held_second_to_the_zone_of_the_earlier_sample() {
        val samples = listOf(
            hr(0.0, 120), // Z0 for 2 s
            hr(2.0, 124), // Z1 for 1 s
            hr(3.0, 150), // Z3 for 3 s
            hr(6.0, 175), // Z5 for 1 s
            hr(7.0, 175), // then a 20 s gap: skipped
            hr(27.0, 100), // Z0 for 1 s
            hr(28.0, 100),
        )
        val byZone = TimeInZone.compute(samples, profile)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), byZone.keys.map { it.number }, "every zone is a key, Z0 first")
        assertEquals(listOf(3.0, 1.0, 0.0, 3.0, 0.0, 1.0), byZone.values.map { it.toDouble(kotlin.time.DurationUnit.SECONDS) })
        assertEquals(HrStream.heldTime(samples), byZone.values.fold(Duration.ZERO) { a, b -> a + b })
    }

    @Test
    fun time_in_zone_of_an_empty_or_single_sample_stream_is_all_zero() {
        for (samples in listOf(emptyList(), listOf(hr(0.0, 150)))) {
            assertEquals(List(6) { Duration.ZERO }, TimeInZone.compute(samples, profile).values.toList())
        }
    }
}
