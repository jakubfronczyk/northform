package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.testing.ProfileStoreContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** profile-settings/01: the profile store contract in memory, the cases the SQLDelight store passes. */
class InMemoryProfileStoreContractTest {
    private fun contract(body: suspend ProfileStoreContract.() -> Unit) = runTest { ProfileStoreContract(InMemoryProfileStore()).body() }

    @Test fun nothing_saved_reads_as_none() = contract { nothing_saved_reads_as_none() }
    @Test fun a_saved_profile_reads_back_exactly() = contract { a_saved_profile_reads_back_exactly() }
    @Test fun a_second_save_replaces_the_first() = contract { a_second_save_replaces_the_first() }
}
