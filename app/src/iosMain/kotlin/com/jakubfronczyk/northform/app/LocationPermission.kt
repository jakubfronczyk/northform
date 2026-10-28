package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.ui.SetupPermissions

/**
 * The platform's location authorization in the UI's words: Setup's Location row and the Gate's reason under GPS
 * (run-flow-ui/07) read the same mapping. The composition root applies it; `:composeApp` never sees `:adapters` (D15).
 */
internal fun LocationAuthorization.asPermission(): SetupPermissions.Location = when (this) {
    LocationAuthorization.NotDetermined -> SetupPermissions.Location.NotAsked
    LocationAuthorization.WhenInUse -> SetupPermissions.Location.Allowed
    LocationAuthorization.Denied -> SetupPermissions.Location.Denied
    LocationAuthorization.Restricted -> SetupPermissions.Location.Restricted
}
