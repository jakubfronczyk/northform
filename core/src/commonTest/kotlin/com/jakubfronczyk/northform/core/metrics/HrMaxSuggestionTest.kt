package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HrMaxSuggestionTest {
    private val profile = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60)
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun hr(s: Double, bpm: Int) = HrSample(t0 + s.seconds, bpm)

    /** 1 Hz from 0 s: `bpms[i]` at i s. */
    private fun stream(vararg bpms: Int) = bpms.mapIndexed { i, bpm -> hr(i.toDouble(), bpm) }

    @Test
    fun the_hold_is_measured_first_sample_to_last_sample_of_the_stretch() {
        val cases = listOf(
            "9 s: ten samples at 1 Hz span 9 s" to (stream(*IntArray(10) { 190 }) to null),
            "10 s: eleven samples" to (stream(*IntArray(11) { 190 }) to 190),
            "9.9 s" to (listOf(hr(0.0, 190), hr(4.0, 191), hr(8.0, 190), hr(9.9, 190)) to null),
            "10.0 s exactly" to (listOf(hr(0.0, 190), hr(4.0, 191), hr(8.0, 190), hr(10.0, 190)) to 191),
            "10.1 s" to (listOf(hr(0.0, 190), hr(4.0, 195), hr(8.0, 190), hr(10.1, 190)) to 195),
        )
        for ((name, case) in cases) assertEquals(case.second, HrMaxSuggestion.detect(case.first, profile), name)
    }

    @Test
    fun one_sample_at_or_below_hrmax_breaks_the_hold() {
        val held = IntArray(11) { 190 }
        val broken = held.copyOf().also { it[5] = 187 } // at HRmax, not above: breaks it into 5 s + 5 s
        assertEquals(190, HrMaxSuggestion.detect(stream(*held), profile))
        assertEquals(null, HrMaxSuggestion.detect(stream(*broken), profile))
    }

    @Test
    fun a_gap_longer_than_five_seconds_breaks_the_hold() {
        val samples = (0..5).map { hr(it.toDouble(), 190) } + (12..17).map { hr(it.toDouble(), 190) } // 5 s + 6 s gap + 5 s
        assertEquals(null, HrMaxSuggestion.detect(samples, profile))
        val bridged = (0..5).map { hr(it.toDouble(), 190) } + (10..15).map { hr(it.toDouble(), 190) } // 4 s gap: one 15 s stretch
        assertEquals(190, HrMaxSuggestion.detect(bridged, profile))
    }

    @Test
    fun invalid_samples_are_dropped_before_the_rule_runs() {
        val overTheCeiling = stream(*IntArray(20) { 240 })
        assertEquals(null, HrMaxSuggestion.detect(overTheCeiling, profile), "> 230 is never a suggestion")
        val belowTheFloor = stream(*IntArray(20) { 20 })
        assertEquals(null, HrMaxSuggestion.detect(belowTheFloor, profile), "< 30 is not a reading")
        // a 240 spike inside a real 190 hold is dropped, not a breaker, not the suggested value
        val spiked = IntArray(11) { 190 }.also { it[5] = 240 }
        assertEquals(190, HrMaxSuggestion.detect(stream(*spiked), profile))
    }

    @Test
    fun the_highest_bpm_of_any_qualifying_stretch_is_suggested() {
        val samples = stream(*IntArray(11) { 189 }) + (20..30).map { hr(it.toDouble(), 192) } + (40..45).map { hr(it.toDouble(), 199) } // last one spans 5 s only
        assertEquals(192, HrMaxSuggestion.detect(samples, profile))
    }

    @Test
    fun nothing_above_hrmax_means_no_suggestion() {
        assertEquals(null, HrMaxSuggestion.detect(stream(*IntArray(60) { 187 }), profile))
        assertEquals(null, HrMaxSuggestion.detect(emptyList(), profile))
    }

    // ── profile snapshots (E15) ──────────────────────────────────────────────────────────────

    private fun effort(): RecordingMetrics.Input {
        val samples = (0..600).map { hr(it.toDouble(), 150) } + (601..660).map { hr(it.toDouble(), 150 - (it - 600)) }
        return RecordingMetrics.Input(samples, stoppedAt = t0 + 600.seconds, cooldownEndedAt = t0 + 660.seconds, activeTime = 10.minutes, rpe = 6)
    }

    @Test
    fun every_metric_row_carries_the_profile_it_was_computed_with() {
        val results = RecordingMetrics.compute(effort(), profile)
        assertEquals(
            listOf("time_in_zone.z0", "time_in_zone.z1", "time_in_zone.z2", "time_in_zone.z3", "time_in_zone.z4", "time_in_zone.z5", "trimp", "srpe", "hr_recovery"),
            results.map { it.metricId.value },
        )
        assertTrue(results.all { it.profile == profile && it.version == RecordingMetrics.version })
        assertEquals(600.0, results.first { it.metricId.value == "time_in_zone.z3" }.value, "150 bpm is Z3 for the whole effort")
        assertEquals(60.0, results.first { it.metricId.value == "srpe" }.value)
        assertEquals(60.0, results.first { it.metricId.value == "hr_recovery" }.value)
        assertTrue(results.none { it.value.isNaN() })
    }

    @Test
    fun recomputing_with_an_edited_profile_changes_the_values_and_the_snapshot_not_the_old_rows() {
        val before = RecordingMetrics.compute(effort(), profile)
        val edited = profile.copy(hrMax = 200)
        val after = RecordingMetrics.compute(effort(), edited)
        assertTrue(before.all { it.profile == profile } && after.all { it.profile == edited })
        assertEquals(600.0, after.first { it.metricId.value == "time_in_zone.z2" }.value, "150 bpm is Z2 under a 200 HRmax")
        assertTrue(after.first { it.metricId.value == "trimp" }.value < before.first { it.metricId.value == "trimp" }.value)
    }

    @Test
    fun a_metric_that_cannot_be_computed_is_left_out() {
        val skipped = effort().copy(cooldownEndedAt = t0 + 605.seconds, rpe = null)
        val ids = RecordingMetrics.compute(skipped, profile).map { it.metricId.value }
        assertTrue("hr_recovery" !in ids && "srpe" !in ids, "$ids")
        val walkOnly = RecordingMetrics.compute(effort().copy(recoveryApplies = false), profile).map { it.metricId.value }
        assertTrue("hr_recovery" !in walkOnly, "D32: not for other recording types")
    }

    @Test
    fun the_shared_valid_range_is_the_profile_ceiling_too() {
        assertEquals(30..230, HeartRate.valid)
        assertFailsWith<Profile.Invalid.HrMax> { Profile.checkHeartRates(max = HeartRate.valid.last + 1, rest = 60) }
        assertFailsWith<Profile.Invalid.HrRest> { Profile.checkHeartRates(max = 187, rest = HeartRate.valid.first - 1) }
        Profile.checkHeartRates(max = HeartRate.valid.last, rest = HeartRate.valid.first)
    }

}
