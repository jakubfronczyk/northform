package com.jakubfronczyk.northform.core

import kotlin.time.Instant

// Ported from northform-ios `Core/Model/Profile.swift` + `Core/Metrics/Zones.swift`. Swift typed
// throws → Kotlin exceptions thrown from `init`, so an instance is always valid (the "always valid"
// invariant the Swift struct guarantees).

/** `Profile.swift:3`. */
enum class Sex(val wire: String) {
    Male("m"), Female("f");

    companion object {
        fun fromWire(wire: String): Sex? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * `Profile.swift:12` — Karvonen zones as fractions of heart-rate reserve: the lower bounds of Z1–Z5,
 * Z0 is everything below. Always valid: 5 bounds, strictly rising, all inside (0, 1).
 */
data class HRZones(val lowerBounds: List<Double>) {
    sealed class Invalid(message: String) : Exception(message) {
        data class WrongCount(val count: Int) : Invalid("wrongCount($count)")
        data object NotRising : Invalid("notRising")
        data object OutOfRange : Invalid("outOfRange")
    }

    init {
        if (lowerBounds.size != 5) throw Invalid.WrongCount(lowerBounds.size)
        if (!lowerBounds.zipWithNext().all { (a, b) -> a < b }) throw Invalid.NotRising
        if (lowerBounds.first() <= 0 || lowerBounds.last() >= 1) throw Invalid.OutOfRange
    }

    companion object {
        /** Z1 50 · Z2 60 · Z3 70 · Z4 80 · Z5 90 % of heart-rate reserve. */
        val standard = HRZones(listOf(0.5, 0.6, 0.7, 0.8, 0.9))
    }
}

/**
 * `Profile.swift:58` — inputs to the heart and load metrics. Always valid, so heart-rate reserve
 * (HRmax − HRrest) is never zero or negative.
 */
data class Profile(
    val birthDate: Instant,
    val sex: Sex,
    val weightKg: Double,
    val heightCm: Double,
    val hrMax: Int,
    /** 60 bpm until measured or set. */
    val hrRest: Int = 60,
    val zones: HRZones = HRZones.standard,
) {
    /** The messages mirror Swift's enum descriptions ("hrMax(60)"), which the fixture errors quote. */
    sealed class Invalid(message: String) : Exception(message) {
        /** Resting HR must be 30–120 bpm. */
        data class HrRest(val bpm: Int) : Invalid("hrRest($bpm)")
        /** Max HR must be above resting HR and at most 230 bpm (above that a sample is invalid). */
        data class HrMax(val bpm: Int) : Invalid("hrMax($bpm)")
        data class WeightKg(val kg: Double) : Invalid("weightKg($kg)")
        data class HeightCm(val cm: Double) : Invalid("heightCm($cm)")
    }

    init {
        checkHeartRates(max = hrMax, rest = hrRest)
        if (weightKg <= 0) throw Invalid.WeightKg(weightKg)
        if (heightCm <= 0) throw Invalid.HeightCm(heightCm)
    }

    /** `Zones.swift:3` — heart-rate reserve fraction: (HR − HRrest) / (HRmax − HRrest) (Karvonen). */
    fun reserveFraction(bpm: Int): Double = (bpm - hrRest).toDouble() / (hrMax - hrRest).toDouble()

    /** `Zones.swift:8` — zone 0–5: the number of zone lower bounds at or below the reserve fraction. */
    fun zone(bpm: Int): Int {
        val fraction = reserveFraction(bpm)
        return zones.lowerBounds.count { fraction >= it }
    }

    companion object {
        /** `Profile.swift:101` — the one definition of valid profile heart rates, shared with replay fixtures. */
        fun checkHeartRates(max: Int, rest: Int) {
            if (rest !in 30..120) throw Invalid.HrRest(rest)
            if (max <= rest || max > 230) throw Invalid.HrMax(max)
        }
    }
}
