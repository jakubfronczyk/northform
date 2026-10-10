package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.math.exp
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * H5 — training load of one recording (spec 08 "TRIMP", Banister 1991):
 * `Σ Δt · ΔHR · k · e^(b·ΔHR)` with Δt in minutes and ΔHR the heart-rate reserve fraction
 * (`Profile.reserveFraction`). Pauses count (spec 04 "H5 covers total time"), HR gaps are skipped
 * ([HrStream]), and an interval below resting HR (ΔHR < 0) adds nothing rather than a negative load.
 * ΔHR is held from the earlier sample of each interval, like [TimeInZone].
 */
object Trimp {
    /** Banister's sex constants: `k · e^(b·ΔHR)`. */
    data class Constants(val k: Double, val b: Double)

    val male = Constants(k = 0.64, b = 1.92)
    val female = Constants(k = 0.86, b = 1.67)

    fun constants(sex: Sex): Constants = when (sex) {
        Sex.Male -> male
        Sex.Female -> female
    }

    fun compute(samples: List<HrSample>, profile: Profile, gap: Duration = HrStream.gap): Double {
        val (k, b) = constants(profile.sex)
        return HrStream.intervals(samples, gap).sumOf { interval ->
            val deltaHr = profile.reserveFraction(interval.bpm)
            if (deltaHr < 0) 0.0 else interval.duration.toDouble(DurationUnit.MINUTES) * deltaHr * k * exp(b * deltaHr)
        }
    }

    /** Session RPE load (spec 08, Foster 2001): RPE 0–10 × active minutes. Shown next to TRIMP, never added to it (Q2). */
    fun sRpe(rpe: Int, active: Duration): Double {
        require(rpe in 0..10) { "rpe $rpe" }
        return rpe * active.toDouble(DurationUnit.MINUTES)
    }
}
