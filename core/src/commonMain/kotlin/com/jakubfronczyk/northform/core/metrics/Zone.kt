package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile

/**
 * One Karvonen zone (spec 08 "Zones", H2, D23): the number 0–5, a name, and the %HRR range it
 * covers. Built from a profile's [HRZones], never by hand, so the range always comes from the
 * stored boundaries (SU2: an HRmax change keeps the %).
 *
 * A `data class` (structural equality), not a Kotlin `value class`: a value class wraps exactly one
 * property, and the range is what the screens show next to the number.
 */
data class Zone(
    val number: Int,
    /** Lower %HRR bound, inclusive; null for Z0 (open-ended: below resting HR the fraction is negative). */
    val lowerFraction: Double?,
    /** Upper %HRR bound, exclusive; null for Z5 (open-ended). */
    val upperFraction: Double?,
) {
    init {
        require(number in 0..5) { "zone number $number" }
    }

    /** "Z0" … "Z5". */
    val label: String get() = "Z$number"

    /** D29: plain effort words, the same table the screens show (Polar's vocabulary, the design canvas). */
    val name: String get() = names[number]

    operator fun contains(fraction: Double): Boolean =
        (lowerFraction == null || fraction >= lowerFraction) && (upperFraction == null || fraction < upperFraction)

    companion object {
        val names: List<String> = listOf("Rest", "Very light", "Light", "Moderate", "Hard", "Maximum")
    }
}

/** The six zones of these boundaries, Z0 first. */
val HRZones.all: List<Zone>
    get() = (0..5).map { zone(it) }

/** Zone `number` of these boundaries: Z0 is everything below the first bound, Z5 everything from the last. */
fun HRZones.zone(number: Int): Zone = Zone(
    number = number,
    lowerFraction = if (number == 0) null else lowerBounds[number - 1],
    upperFraction = lowerBounds.getOrNull(number),
)

/** `Zones.swift:8` — the zone of a heart-rate-reserve fraction: the number of lower bounds at or below it. */
fun HRZones.zoneOf(fraction: Double): Zone = zone(lowerBounds.count { fraction >= it })

/** The zone of a heart rate under this profile (Karvonen, `Profile.reserveFraction`). */
fun Profile.zoneOf(bpm: Int): Zone = zones.zoneOf(reserveFraction(bpm))
