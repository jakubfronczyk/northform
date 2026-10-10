package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LocationAuthorization

/**
 * What the gate says next to "GPS ✗" when no fix can ever come (adapters/03; the Swift `locationDenied`
 * guard, `AppModel.swift:32,68`, restated as copy instead of a hidden Start). The start gate itself is the
 * reducer's (engine/01, option A): with no fixes `gpsLost` stays true and Start is held, "Start anyway"
 * remains the person's call. `canOpenSettings` pairs with `NorthformApp.openLocationSettings()`.
 */
data class LocationHint(val title: String, val detail: String, val canOpenSettings: Boolean) {
    companion object {
        /** `null` while nothing stands in the way (granted, or not asked yet: the run asks on open). */
        fun of(status: LocationAuthorization): LocationHint? = when (status) {
            LocationAuthorization.WhenInUse, LocationAuthorization.NotDetermined -> null
            LocationAuthorization.Denied -> LocationHint(
                "Location is off for Northform",
                "Turn it on in Settings → Northform → Location, then come back: GPS restarts by itself.",
                canOpenSettings = true,
            )
            LocationAuthorization.Restricted -> LocationHint(
                "Location is restricted on this phone",
                "Screen Time or a device profile blocks it; this app cannot change that.",
                canOpenSettings = false,
            )
        }
    }
}
