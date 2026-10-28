package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.ports.ProfileStore

/**
 * For now (Jakub, 2026-10-28: "hardcode the data for profile for now", to go through the UI): a store with nothing saved
 * answers with [profile] instead of null, so the app opens on Today rather than Setup. Nothing is written; a profile
 * saved in Settings replaces it. Turned on and off by [NorthformApp.HARDCODED_PROFILE].
 */
class HardcodedProfile(private val store: ProfileStore, private val profile: Profile) : ProfileStore by store {
    override suspend fun profile(): Profile = store.profile() ?: profile
}
