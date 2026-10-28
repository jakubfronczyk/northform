package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.testing.SavedRecordingsContract
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

/** today-screen/01: the saved-recordings read on SQLDelight, one fresh database per case (closed after, see [SqlRecordingStoreContractTest]). */
class SqlSavedRecordingsContractTest {
    private fun contract(body: suspend SavedRecordingsContract<SqlRecordingStore>.() -> Unit) = runTest {
        val driver = inMemoryDriver()
        try {
            SavedRecordingsContract(SqlRecordingStore(driver, io = EmptyCoroutineContext)).body()
        } finally {
            driver.close()
        }
    }

    @Test fun newest_first_only_saved_and_the_limit_is_honoured() = contract { newest_first_only_saved_and_the_limit_is_honoured() }
    @Test fun the_stored_headline_and_the_tag_read_back_and_a_walk_has_none() = contract { the_stored_headline_and_the_tag_read_back_and_a_walk_has_none() }
    @Test fun a_recording_without_the_headline_rows_is_computed_from_its_streams() = contract { a_recording_without_the_headline_rows_is_computed_from_its_streams() }
}
