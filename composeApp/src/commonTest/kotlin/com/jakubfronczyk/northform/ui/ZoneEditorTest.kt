package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The zone editor's rules (`ZoneEditor.dc.html`) as a table over [ZoneEditor] (profile-settings/04). */
class ZoneEditorTest {
    private val profile = Profile(Instant.parse("1996-03-14T00:00:00Z"), Sex.Male, 78.0, 182.0, hrMax = 187, hrRest = 60)
    private val standard = ZoneEditor.of(profile)

    @Test
    fun the_rows_are_z1_to_z5_with_their_name_percent_and_bpm() {
        val rows = standard.rows
        assertEquals(listOf("Z1 Very light", "Z2 Light", "Z3 Moderate", "Z4 Hard", "Z5 Maximum"), rows.map { it.label })
        assertEquals(listOf(50, 60, 70, 80, 90), rows.map { it.percent })
        assertEquals(listOf(124, 136, 149, 162, 174), rows.map { it.bpm }, "round(60 + p/100 × 127)")
        assertEquals("Preview · max 187 · resting 60 bpm", standard.preview)
    }

    @Test
    fun a_stepper_moves_one_point_and_the_bpm_follows() {
        val raised = standard.raise(2)
        assertEquals(71, raised.rows[2].percent)
        assertEquals(150, raised.rows[2].bpm)
        assertEquals(70, raised.lower(2).rows[2].percent)
    }

    @Test
    fun z1_stops_at_30_and_z5_at_98() {
        var z = standard
        repeat(30) { z = z.lower(0) }
        assertEquals(30, z.rows[0].percent)
        assertFalse(z.rows[0].canLower)
        var top = standard
        repeat(30) { top = top.raise(4) }
        assertEquals(98, top.rows[4].percent)
        assertFalse(top.rows[4].canRaise)
    }

    @Test
    fun each_bound_stays_two_points_from_its_neighbours() {
        var z = standard
        repeat(20) { z = z.raise(1) }
        assertEquals(68, z.rows[1].percent, "Z3 starts at 70")
        assertFalse(z.rows[1].canRaise)
        assertFalse(z.rows[2].canLower)
        assertTrue(z.rows[2].canRaise)
    }

    @Test
    fun reset_returns_50_60_70_80_90_and_saving_builds_the_zones() {
        val edited = standard.raise(0).lower(4)
        assertEquals(HRZones(listOf(0.51, 0.6, 0.7, 0.8, 0.89)), edited.zones)
        assertEquals(listOf(50, 60, 70, 80, 90), edited.reset().rows.map { it.percent })
    }

    @Test
    fun a_profile_s_own_zones_open_as_whole_percent() {
        val own = ZoneEditor.of(profile.copy(zones = HRZones(listOf(0.53, 0.64, 0.72, 0.83, 0.91))))
        assertEquals(listOf(53, 64, 72, 83, 91), own.rows.map { it.percent })
    }

    @Test
    fun a_different_max_and_rest_keep_the_percent_and_move_the_bpm() {
        val other = ZoneEditor.of(profile.copy(hrMax = 200, hrRest = 50))
        assertEquals(listOf(50, 60, 70, 80, 90), other.rows.map { it.percent })
        assertEquals(125, other.rows[0].bpm, "50 + 0.5 × 150")
    }

    /** Stored bounds that round to the same whole percent can't be edited safely: the editor opens on the defaults. */
    @Test
    fun zones_that_collide_when_rounded_open_on_the_defaults() {
        val close = ZoneEditor.of(profile.copy(zones = HRZones(listOf(0.5, 0.6, 0.705, 0.709, 0.9))))
        assertEquals(ZoneEditor.DEFAULT, close.rows.map { it.percent })
    }

    /** The preview bar: six segments, Z0 ten points wide, each zone as wide as its range up to 100 %. */
    @Test
    fun the_bar_weights_each_zone_by_its_width() {
        assertEquals(listOf(10, 10, 10, 10, 10, 10), standard.bar)
        assertEquals(listOf(10, 9, 11, 10, 10, 10), standard.lower(1).bar, "Z2 starts at 59: Z1 is 50→59, Z2 59→70")
    }
}
