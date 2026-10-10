package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** adapters/03: the gate's reason + Settings link per authorization state (a table). */
class LocationHintTest {
    @Test
    fun granted_and_not_yet_asked_show_nothing() {
        assertNull(LocationHint.of(LocationAuthorization.WhenInUse))
        assertNull(LocationHint.of(LocationAuthorization.NotDetermined))
    }

    @Test
    fun denied_names_the_switch_and_offers_settings() {
        val hint = LocationHint.of(LocationAuthorization.Denied)!!
        assertEquals("Location is off for Northform", hint.title)
        assertTrue(hint.canOpenSettings)
        assertTrue("Settings" in hint.detail)
    }

    @Test
    fun restricted_explains_and_does_not_offer_settings() {
        val hint = LocationHint.of(LocationAuthorization.Restricted)!!
        assertEquals("Location is restricted on this phone", hint.title)
        assertEquals(false, hint.canOpenSettings)
    }

    @Test
    fun only_denied_and_restricted_block_location() {
        assertEquals(listOf(LocationAuthorization.Denied, LocationAuthorization.Restricted), LocationAuthorization.entries.filter { it.blocksLocation })
    }
}
