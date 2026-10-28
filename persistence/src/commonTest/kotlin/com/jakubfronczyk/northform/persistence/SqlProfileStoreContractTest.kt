package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.testing.ProfileStoreContract
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

/** profile-settings/01: the profile store contract on SQLDelight, one fresh database per case (closed after). */
class SqlProfileStoreContractTest {
    private fun contract(body: suspend ProfileStoreContract.() -> Unit) = runTest {
        val driver = inMemoryDriver()
        try {
            ProfileStoreContract(SqlRecordingStore(driver, io = EmptyCoroutineContext)).body()
        } finally {
            driver.close()
        }
    }

    @Test fun nothing_saved_reads_as_none() = contract { nothing_saved_reads_as_none() }
    @Test fun a_saved_profile_reads_back_exactly() = contract { a_saved_profile_reads_back_exactly() }
    @Test fun a_second_save_replaces_the_first() = contract { a_second_save_replaces_the_first() }
}
