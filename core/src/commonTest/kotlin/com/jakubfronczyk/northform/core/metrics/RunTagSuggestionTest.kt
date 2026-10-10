package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RunTagSuggestionTest {
    // reserve 127: Z1 123.5 · Z2 136.2 · Z3 148.9 · Z4 161.6 · Z5 174.3 bpm
    private val profile = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60)
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private val z1 = 130
    private val z2 = 140
    private val z3 = 155
    private val z4 = 165

    /** A 1 Hz stream built from (bpm, seconds) legs, one sample per second, plus a closing sample so the last leg is held in full. */
    private fun stream(vararg legs: Pair<Int, Int>): List<HrSample> {
        val out = ArrayList<HrSample>()
        var s = 0
        for ((bpm, seconds) in legs) {
            repeat(seconds) { out += HrSample(t0 + s.seconds, bpm); s++ }
        }
        out += HrSample(t0 + s.seconds, legs.last().first)
        return out
    }

    private fun suggest(samples: List<HrSample>, total: Duration = 40.minutes, distance: Double = 8_000.0, type: RecordingType = RecordingType.Run) =
        RunTagSuggestion.suggest(type, samples, profile, total, distance)

    private fun swings(n: Int, high: Int = 30, low: Int = 30): List<HrSample> {
        val legs = ArrayList<Pair<Int, Int>>()
        legs += z4 to high
        repeat(n) { legs += z2 to low; legs += z4 to high }
        return stream(*legs.toTypedArray())
    }

    // ── one fixture per tag ───────────────────────────────────────────────────────────────────

    @Test
    fun intervals_three_swings_from_z4_to_z2_and_back() = assertEquals(RunTag.Intervals, suggest(swings(3)))

    @Test
    fun tempo_twenty_minutes_in_z3_and_z4() = assertEquals(RunTag.Tempo, suggest(stream(z1 to 300, z3 to 600, z4 to 600, z1 to 300)))

    @Test
    fun long_by_time_or_distance_in_easy_zones() {
        val easy = stream(z1 to 600)
        assertEquals(RunTag.Long, suggest(easy, total = 75.minutes, distance = 8_000.0))
        assertEquals(RunTag.Long, suggest(easy, total = 40.minutes, distance = 15_000.0))
    }

    @Test
    fun easy_otherwise() = assertEquals(RunTag.Easy, suggest(stream(z1 to 600, z2 to 600)))

    @Test
    fun a_walk_has_no_tag() = assertNull(suggest(swings(3), type = RecordingType.Walk))

    // ── edges at every threshold ──────────────────────────────────────────────────────────────

    @Test
    fun two_swings_are_not_intervals_three_are() {
        assertEquals(2, RunTagSuggestion.countSwings(swings(2), profile))
        assertEquals(RunTag.Easy, suggest(swings(2)))
        assertEquals(RunTag.Intervals, suggest(swings(3)))
    }

    @Test
    fun a_leg_held_under_thirty_seconds_does_not_count() {
        assertEquals(RunTag.Easy, suggest(swings(3, low = 29)), "29 s recoveries")
        assertEquals(RunTag.Easy, suggest(swings(3, high = 29)), "29 s efforts")
        assertEquals(RunTag.Intervals, suggest(swings(3, high = 30, low = 30)), "exactly 30 s")
    }

    @Test
    fun z3_between_the_legs_is_neutral_but_a_gap_breaks_a_leg() {
        // down through Z3 and up again: still a swing
        val through = stream(z4 to 30, z3 to 10, z2 to 30, z3 to 10, z4 to 30, z3 to 10, z2 to 30, z4 to 30, z2 to 30, z4 to 30)
        assertEquals(3, RunTagSuggestion.countSwings(through, profile))
        // a 20 s + 20 s effort split by a 6 s HR gap never holds 30 s
        val gap = stream(z4 to 20) + stream(z4 to 20).map { HrSample(it.t + 26.seconds, it.bpm) } + stream(z2 to 30).map { HrSample(it.t + 60.seconds, it.bpm) }
        assertEquals(0, RunTagSuggestion.countSwings(gap, profile))
        assertEquals(RunTag.Easy, suggest(gap + swings(2).map { HrSample(it.t + 200.seconds, it.bpm) }), "the broken effort does not start a swing")
    }

    @Test
    fun a_fourth_high_after_the_third_low_is_what_closes_the_third_swing() {
        val openEnded = stream(z4 to 30, z2 to 30, z4 to 30, z2 to 30, z4 to 30, z2 to 30)
        assertEquals(2, RunTagSuggestion.countSwings(openEnded, profile), "the last recovery has no effort after it")
    }

    @Test
    fun tempo_needs_twenty_full_minutes() {
        assertEquals(RunTag.Easy, suggest(stream(z3 to 1199)), "19:59")
        assertEquals(RunTag.Tempo, suggest(stream(z3 to 1200)), "20:00")
        assertEquals(RunTag.Tempo, suggest(stream(z3 to 600, z1 to 60, z4 to 600)), "Z3 + Z4 add up, pauses in between count for nothing")
    }

    @Test
    fun long_thresholds_are_inclusive() {
        val easy = stream(z1 to 60)
        assertEquals(RunTag.Easy, suggest(easy, total = 74.minutes + 59.seconds, distance = 14_999.0))
        assertEquals(RunTag.Long, suggest(easy, total = 75.minutes, distance = 0.0))
        assertEquals(RunTag.Long, suggest(easy, total = 1.minutes, distance = 15_000.0))
    }

    @Test
    fun first_match_wins_intervals_over_tempo_over_long() {
        val intervalsAndTempo = swings(3, high = 400, low = 30) // 4 × 400 s ≥ Z4: also 20 min of tempo
        assertEquals(RunTag.Intervals, suggest(intervalsAndTempo, total = 80.minutes, distance = 16_000.0))
        assertEquals(RunTag.Tempo, suggest(stream(z3 to 1200), total = 80.minutes, distance = 16_000.0))
    }
}
