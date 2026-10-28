package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.Profile

/**
 * Where the runner's current profile is kept (profile-settings spec). Beside the frozen [RecordingStore], like
 * [ReliabilityStore]. One profile per install; every saved run keeps its own copy in its metric rows (D24, E15), so
 * replacing this one never changes history.
 */
interface ProfileStore {
    /** The saved profile, or null before the runner has saved one. */
    suspend fun profile(): Profile?

    /** Replaces the saved profile. */
    suspend fun saveProfile(profile: Profile)
}
