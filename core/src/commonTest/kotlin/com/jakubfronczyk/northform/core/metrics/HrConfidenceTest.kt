package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HrConfidenceTest {
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun at(s: Int) = t0 + s.seconds
    private fun hr(s: Int, bpm: Int) = HrSample(at(s), bpm)

    /** One fix per second heading north at `speed` m/s (3 m/s ≈ 5:33 /km); `speed` null = the fix carries no speed. */
    private fun fixes(from: Int, until: Int, speed: Double?, still: Boolean? = null) = (from..until).map { s ->
        LocationFix(at(s), 52.0 + (s * (speed ?: 0.0)) / 111_195, 13.0, horizontalAccuracy = 5.0, speed = speed, stationary = still)
    }

    /**
     * The synthetic fixture: a 5 min run at 3 m/s; the sensor reads 150–152 bpm with 1 Hz wobble,
     * except from 100 s to 130 s where it freezes at 151.
     */
    private val runHr = (0..300).map { s -> hr(s, if (s in 100..130) 151 else 150 + s % 3) }
    private val runFixes = fixes(0, 300, speed = 3.0)

    @Test
    fun a_freeze_while_running_is_flagged_as_exactly_that_stretch() {
        assertEquals(listOf(HrConfidence.Stretch(at(100), at(130))), HrConfidence.lowConfidence(runHr, runFixes))
    }

    @Test
    fun no_flag_while_standing() {
        val cases = listOf(
            "speed 0" to fixes(0, 300, speed = 0.0, still = true),
            "slow drift under the bar" to fixes(0, 300, speed = 0.4),
            "fixes without speed" to fixes(0, 300, speed = null),
            "no fixes at all" to emptyList(),
            "moving before and after, standing during the freeze" to (fixes(0, 99, 3.0) + fixes(100, 130, 0.0) + fixes(131, 300, 3.0)),
        )
        for ((name, fixes) in cases) assertEquals(emptyList(), HrConfidence.lowConfidence(runHr, fixes), name)
    }

    @Test
    fun the_stretch_must_be_longer_than_twenty_seconds() {
        fun freeze(seconds: Int) = (0..120).map { s -> hr(s, if (s in 50..(50 + seconds)) 140 else 150 + s % 3) } // 140 is outside the wobble, so the neighbours never extend it
        val cases = listOf(19 to 0, 20 to 0, 21 to 1) // span first → last sample; 20 s is not "> 20 s"
        for ((seconds, flagged) in cases) assertEquals(flagged, HrConfidence.lowConfidence(freeze(seconds), fixes(0, 120, 3.0)).size, "$seconds s flat")
    }

    @Test
    fun a_gap_or_a_one_bpm_wobble_ends_the_flat_stretch() {
        val withGap = runHr.filter { (it.t - t0).inWholeSeconds !in 112..118 } // 6 s gap in the middle of the freeze: 12 s + 12 s
        assertEquals(emptyList(), HrConfidence.lowConfidence(withGap, runFixes), "two short flats, not one long one")
        val withWobble = runHr.map { if (it.t == at(115)) HrSample(it.t, 152) else it }
        assertEquals(emptyList(), HrConfidence.lowConfidence(withWobble, runFixes), "one different sample ends the stretch")
        val withInvalidInside = runHr.map { if (it.t == at(115)) HrSample(it.t, 250) else it } // dropped, 114 → 116 is a 2 s step: still flat
        assertEquals(listOf(HrConfidence.Stretch(at(100), at(130))), HrConfidence.lowConfidence(withInvalidInside, runFixes))
    }

    @Test
    fun the_fixes_that_count_are_the_ones_inside_the_stretch() {
        // moving only for the first half of the freeze: mean 1.5 m/s > 0.5 ⇒ flagged; moving a tenth of it: 0.3 ⇒ not
        val halfMoving = fixes(100, 115, 3.0) + fixes(116, 130, 0.0)
        assertEquals(1, HrConfidence.lowConfidence(runHr, halfMoving).size)
        val barelyMoving = fixes(100, 102, 3.0) + fixes(103, 130, 0.0)
        assertEquals(0, HrConfidence.lowConfidence(runHr, barelyMoving).size)
    }

    @Test
    fun several_freezes_are_reported_in_order() {
        val hr = (0..300).map { s -> hr(s, if (s in 30..60 || s in 200..240) 140 else 150 + s % 3) }
        assertEquals(listOf(HrConfidence.Stretch(at(30), at(60)), HrConfidence.Stretch(at(200), at(240))), HrConfidence.lowConfidence(hr, runFixes))
    }
}
