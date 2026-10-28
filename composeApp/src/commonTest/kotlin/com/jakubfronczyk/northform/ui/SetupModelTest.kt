package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.SetupPermissions.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** The Setup steps' rules as tables (first-launch-setup/02–03; `Setup*.dc.html`). */
class SetupModelTest {
    private val profile = Profile(Instant.parse("1996-03-14T00:00:00Z"), Sex.Male, 78.0, 182.0, hrMax = 187, hrRest = 60)

    @Test
    fun the_steps_go_in_d71_order_and_back_stops_at_the_first() {
        assertEquals(listOf("1 of 4", "2 of 4", "3 of 4", "4 of 4"), SetupStep.entries.map { it.progress })
        assertEquals(SetupStep.Permissions, SetupStep.AboutYou.next())
        assertEquals(SetupStep.Zones, SetupStep.Pair.next())
        assertNull(SetupStep.Zones.next(), "after the last step: Today")
        assertNull(SetupStep.AboutYou.back(), "step 1 has no Back")
        assertEquals(SetupStep.Pair, SetupStep.Zones.back())
    }

    @Test
    fun the_zones_step_reads_as_the_artboard() {
        val z = SetupZones.of(profile)
        assertEquals("From max 187 and resting 60 bpm, as % of heart rate reserve.", z.subtitle)
        assertEquals(listOf("Z0 Rest", "Z1 Very light", "Z2 Light", "Z3 Moderate", "Z4 Hard", "Z5 Maximum"), z.rows.map { it.label })
        assertEquals(listOf("below 50 %", "50–60 %", "60–70 %", "70–80 %", "80–90 %", "90–100 %"), z.rows.map { it.percent })
        assertEquals(listOf("below 124", "124–136", "136–149", "149–162", "162–174", "174–187"), z.rows.map { it.bpm })
    }

    @Test
    fun the_zones_step_follows_the_runner_s_own_zones() {
        val own = SetupZones.of(profile.copy(zones = HRZones(listOf(0.55, 0.65, 0.75, 0.85, 0.92))))
        assertEquals("below 55 %", own.rows[0].percent)
        assertEquals("85–92 %", own.rows[4].percent)
    }

    @Test
    fun the_permission_rows_follow_what_ios_says() {
        val asked = SetupPermissions.of(bluetoothAllowed = true, location = Location.NotAsked)
        assertEquals(SetupPermissions.Row("Allowed", action = null), asked.bluetooth)
        assertEquals(SetupPermissions.Row("Not allowed", action = SetupPermissions.Action.Allow), asked.location)
        assertNull(asked.locationNote)

        val denied = SetupPermissions.of(bluetoothAllowed = false, location = Location.Denied)
        assertEquals(SetupPermissions.Row("Not allowed", action = SetupPermissions.Action.OpenSettings), denied.bluetooth)
        assertEquals(SetupPermissions.Row("Not allowed", action = SetupPermissions.Action.OpenSettings), denied.location)
        assertEquals("Location is off for this app. Turn it on in iOS Settings, then come back.", denied.locationNote)

        assertEquals(SetupPermissions.Row("Not allowed", action = null), SetupPermissions.of(true, Location.Restricted).location, "restricted: nothing the runner can change")
        assertEquals(SetupPermissions.Row("Allowed", action = null), SetupPermissions.of(true, Location.Allowed).location)
    }
}
