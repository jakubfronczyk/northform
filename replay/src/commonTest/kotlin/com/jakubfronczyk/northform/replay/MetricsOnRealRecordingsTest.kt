package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.HrStream
import com.jakubfronczyk.northform.core.metrics.RunTagSuggestion
import com.jakubfronczyk.northform.core.metrics.TimeInZone
import com.jakubfronczyk.northform.core.metrics.Trimp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The summary metrics (`:core/metrics`) on the two real recordings. The fixtures are parsed by
 * `:replay`, so the test lives here; nothing in replay main is touched.
 */
class MetricsOnRealRecordingsTest {

    private class Run(val samples: List<HrSample>, val startedAt: Instant, val stoppedAt: Instant, val profile: Profile, val type: com.jakubfronczyk.northform.core.RecordingType)

    /** The samples between start and stop (what the store holds for the run), and the fixture's profile. */
    private fun run(name: String): Run {
        val script = bundled(name)
        val users = script.inputs.filterIsInstance<ReplayInput.User>()
        val startedAt = users.first { it.action == UserAction.Start }.t
        val stoppedAt = users.first { it.action == UserAction.Stop }.t
        val samples = script.inputs.filterIsInstance<ReplayInput.Hr>().map { it.sample }.filter { it.t >= startedAt && it.t <= stoppedAt }
        val profile = Profile(Instant.fromEpochSeconds(0), script.meta.sex, weightKg = 75.0, heightCm = 180.0, hrMax = script.meta.hrMax, hrRest = script.meta.hrRest)
        return Run(samples, startedAt, stoppedAt, profile, script.meta.type)
    }

    private fun Map<*, Duration>.total() = values.fold(Duration.ZERO) { a, b -> a + b }

    // ── metrics/01 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun time_in_zone_sums_to_the_held_hr_time_on_the_real_recordings() {
        for (name in listOf("run-1-park", "run-2-walk")) {
            val run = run(name)
            val byZone = TimeInZone.compute(run.samples, run.profile)
            // The held time computed the long way, independent of HrStream: Σ(dt) over consecutive pairs ≤ 5 s.
            val held = run.samples.zipWithNext { a, b -> b.t - a.t }.filter { it <= 5.seconds }.fold(Duration.ZERO) { x, y -> x + y }
            assertEquals(held, byZone.total(), name)
            assertEquals(held, HrStream.heldTime(run.samples), name)
            val span = run.samples.last().t - run.samples.first().t
            assertTrue(held > span * 0.9, "$name: ${held.inWholeSeconds} s held of ${span.inWholeSeconds} s: the sensor rarely dropped")
        }
    }

    /** Characterisation pins (no Swift reference exists): run 1 is a mostly Z3–Z4 run, run 2 a Z0–Z1 walk. */
    @Test
    fun time_in_zone_on_the_real_recordings_stays_put() {
        val run1 = TimeInZone.compute(run("run-1-park").samples, run("run-1-park").profile).values.map { it.inWholeMilliseconds }
        val run2 = TimeInZone.compute(run("run-2-walk").samples, run("run-2-walk").profile).values.map { it.inWholeMilliseconds }
        assertEquals(listOf<Long>(308_954, 278_106, 580_272, 1_086_670, 190_918, 0), run1, "run-1-park Z0…Z5 ms")
        assertEquals(listOf<Long>(2_417_981, 3_000, 0, 0, 0, 0), run2, "run-2-walk Z0…Z5 ms")
    }

    // ── metrics/02 ─────────────────────────────────────────────────────────────────────────────

    /** The pinned TRIMP of the real recordings (characterisation, no Swift reference; male constants, the fixture profile). */
    @Test
    fun trimp_on_the_real_recordings_stays_put() {
        val run1 = Trimp.compute(run("run-1-park").samples, run("run-1-park").profile)
        val run2 = Trimp.compute(run("run-2-walk").samples, run("run-2-walk").profile)
        assertEquals("64.535", fmt(run1, 3), "run-1-park")
        assertEquals("16.483", fmt(run2, 3), "run-2-walk")
        assertTrue(run1 > 3 * run2, "a 42 min Z3 run loads far more than a 41 min Z0 walk (64.5 vs 16.5)")
    }

    // ── metrics/05 ─────────────────────────────────────────────────────────────────────────────

    /** Run 1 spent 21.3 min in Z3–Z4 (the metrics/01 pins) and swung above Z4 too rarely for intervals: tempo. Run 2 is a walk: no tag. */
    @Test
    fun run_tag_on_the_real_recordings_stays_put() {
        val run1 = run("run-1-park")
        assertEquals(RunTag.Tempo, RunTagSuggestion.suggest(run1.type, run1.samples, run1.profile, run1.stoppedAt - run1.startedAt, 4829.7))
        assertEquals(SWINGS_RUN1, RunTagSuggestion.countSwings(run1.samples, run1.profile), "run-1-park swings")
        // The fixture was recorded before the app had a walk type (`meta.type` says run): tag it as what it was.
        val run2 = run("run-2-walk")
        assertEquals(null, RunTagSuggestion.suggest(com.jakubfronczyk.northform.core.RecordingType.Walk, run2.samples, run2.profile, run2.stoppedAt - run2.startedAt, 2987.5))
        assertEquals(RunTag.Easy, RunTagSuggestion.suggest(run2.type, run2.samples, run2.profile, run2.stoppedAt - run2.startedAt, 2987.5), "the same stream as a run would be easy")
    }

    private val SWINGS_RUN1 = 0

    /** `String(format: "%.<d>f")` for a non-negative value. */
    private fun fmt(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = kotlin.math.round(value * scale).toLong()
        return "${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
    }

    @Test
    fun the_fixture_profiles_are_the_standard_male_profile() {
        for (name in listOf("run-1-park", "run-2-walk")) assertEquals(Sex.Male to 187, run(name).profile.let { it.sex to it.hrMax }, name)
    }
}
