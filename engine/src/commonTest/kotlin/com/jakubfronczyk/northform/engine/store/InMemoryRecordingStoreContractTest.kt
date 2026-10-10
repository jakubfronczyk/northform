package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.testing.RecordingStoreContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The port contract on the in-memory store: the same cases the SQLDelight store passes. */
class InMemoryRecordingStoreContractTest {
    private fun contract(body: suspend RecordingStoreContract.() -> Unit) = runTest { RecordingStoreContract(InMemoryRecordingStore()).body() }

    @Test fun create_then_load_round_trips_the_recording_with_no_data() = contract { create_then_load_round_trips_the_recording_with_no_data() }
    @Test fun create_refuses_a_duplicate_and_a_non_active_recording() = contract { create_refuses_a_duplicate_and_a_non_active_recording() }
    @Test fun flush_appends_in_order_and_round_trips_every_field() = contract { flush_appends_in_order_and_round_trips_every_field() }
    @Test fun every_event_kind_round_trips() = contract { every_event_kind_round_trips() }
    @Test fun flush_needs_a_recording_in_active_or_stopping() = contract { flush_needs_a_recording_in_active_or_stopping() }
    @Test fun stop_writes_end_and_the_stop_event_together() = contract { stop_writes_end_and_the_stop_event_together() }
    @Test fun the_state_machine_allows_one_step_at_a_time() = contract { the_state_machine_allows_one_step_at_a_time() }
    @Test fun save_summary_finishes_the_recording() = contract { save_summary_finishes_the_recording() }
    @Test fun unfinished_is_the_recording_a_crash_left_behind() = contract { unfinished_is_the_recording_a_crash_left_behind() }
    @Test fun delete_removes_everything_and_an_unknown_id_is_an_error() = contract { delete_removes_everything_and_an_unknown_id_is_an_error() }
}
