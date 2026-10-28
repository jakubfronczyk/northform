package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.ui.SetupPermissions
import kotlin.test.Test
import kotlin.test.assertEquals

/** adapters/03 + run-flow-ui/07: what the location authorization means to the adapter and to the UI. */
class LocationAuthorizationTest {

    /** D101: in these two no fix will ever come, so `LiveLocation` does not restart updates. */
    @Test
    fun only_denied_and_restricted_block_location() {
        assertEquals(listOf(LocationAuthorization.Denied, LocationAuthorization.Restricted), LocationAuthorization.entries.filter { it.blocksLocation })
    }

    /** One mapping drives Setup's Location row and the Gate's reason under GPS. */
    @Test
    fun each_authorization_maps_to_the_uis_location() {
        val table = mapOf(
            LocationAuthorization.NotDetermined to SetupPermissions.Location.NotAsked,
            LocationAuthorization.WhenInUse to SetupPermissions.Location.Allowed,
            LocationAuthorization.Denied to SetupPermissions.Location.Denied,
            LocationAuthorization.Restricted to SetupPermissions.Location.Restricted,
        )
        assertEquals(LocationAuthorization.entries.toSet(), table.keys, "every state is mapped")
        for ((authorization, location) in table) assertEquals(location, authorization.asPermission(), "$authorization")
    }
}
