package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.testing.SavedRecordingsContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** today-screen/01: the saved-recordings read on the in-memory store, the cases the SQLDelight store passes. */
class InMemorySavedRecordingsContractTest {
    private fun contract(body: suspend SavedRecordingsContract<InMemoryRecordingStore>.() -> Unit) = runTest { SavedRecordingsContract(InMemoryRecordingStore()).body() }

    @Test fun newest_first_only_saved_and_the_limit_is_honoured() = contract { newest_first_only_saved_and_the_limit_is_honoured() }
    @Test fun the_stored_headline_and_the_tag_read_back_and_a_walk_has_none() = contract { the_stored_headline_and_the_tag_read_back_and_a_walk_has_none() }
    @Test fun a_recording_without_the_headline_rows_is_computed_from_its_streams() = contract { a_recording_without_the_headline_rows_is_computed_from_its_streams() }
}
