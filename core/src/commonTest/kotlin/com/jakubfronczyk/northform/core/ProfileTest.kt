package com.jakubfronczyk.northform.core

import com.jakubfronczyk.northform.core.metrics.Geo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProfileTest {
    private val profile = Profile(Instant.fromEpochSeconds(0), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 60)

    @Test
    fun karvonen_zones_count_lower_bounds_at_or_below_the_reserve_fraction() {
        // reserve = 127; 50 % → 123.5 bpm, 60 % → 136.2, 70 % → 148.9, 80 % → 161.6, 90 % → 174.3
        assertEquals(0, profile.zone(120))
        assertEquals(1, profile.zone(124))
        assertEquals(3, profile.zone(150))
        assertEquals(5, profile.zone(180))
    }

    @Test
    fun invalid_heart_rates_throw_with_the_swift_message() {
        assertEquals("hrMax(60)", assertFailsWith<Profile.Invalid.HrMax> { Profile.checkHeartRates(max = 60, rest = 60) }.message)
        assertEquals("hrRest(20)", assertFailsWith<Profile.Invalid.HrRest> { Profile.checkHeartRates(max = 187, rest = 20) }.message)
        assertFailsWith<HRZones.Invalid.NotRising> { HRZones(listOf(0.5, 0.5, 0.7, 0.8, 0.9)) }
    }

    @Test
    fun haversine_one_degree_of_latitude_is_about_111_km() {
        val a = LocationFix(Instant.fromEpochSeconds(0), 52.0, 13.0, 5.0)
        val b = LocationFix(Instant.fromEpochSeconds(0), 53.0, 13.0, 5.0)
        assertTrue(abs(Geo.distance(a, b) - 111_195) < 1, "${Geo.distance(a, b)} m")
    }
}
