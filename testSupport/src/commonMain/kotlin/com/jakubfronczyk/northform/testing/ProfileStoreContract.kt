package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.ports.ProfileStore
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * profile-settings/01 — what every [ProfileStore] must do, run once per adapter (in memory, SQLDelight) with a fresh
 * store per case, like [RecordingStoreContract].
 */
class ProfileStoreContract(private val store: ProfileStore) {
    /** Every field off its default, zones included, with values that would show a lossy encoding. */
    private val mine = Profile(
        birthDate = Instant.parse("1989-07-04T00:00:00Z"), sex = Sex.Female, weightKg = 61.3, heightCm = 168.5,
        hrMax = 194, hrRest = 47, zones = HRZones(listOf(0.53, 0.64, 0.72, 0.83, 0.91)),
    )

    suspend fun nothing_saved_reads_as_none() = assertNull(store.profile())

    suspend fun a_saved_profile_reads_back_exactly() {
        store.saveProfile(mine)
        assertEquals(mine, store.profile())
    }

    suspend fun a_second_save_replaces_the_first() {
        store.saveProfile(mine)
        val updated = mine.copy(hrMax = 199, zones = HRZones.standard)
        store.saveProfile(updated)
        assertEquals(updated, store.profile())
    }
}
