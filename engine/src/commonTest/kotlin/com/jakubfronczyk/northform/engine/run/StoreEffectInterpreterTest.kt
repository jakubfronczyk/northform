package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStoreError
import com.jakubfronczyk.northform.testing.FlakyStore
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one store-effect interpreter the session's writer and the harness both run (D5, D94, D112). */
class StoreEffectInterpreterTest {
    private val id = RunHarness.config.recordingId
    private val recording = Recording(id = id, type = RunHarness.config.type, state = RecordingState.Active, startUtc = at(5), tzOffset = 7200)
    private fun batch(seq: Int) = RunEffect.Persist(seq, FlushBatch(id, hr = listOf(HrSample(at(6), 120)), persistedAt = at(6 + seq)))

    @Test
    fun a_flushed_batch_is_confirmed_to_the_reducer() = runTest {
        val store = FlakyStore()
        val interpreter = StoreEffectInterpreter(store, id)
        assertNull(interpreter.perform(RunEffect.Create(recording)))
        assertEquals(RunInput.Persisted(1), interpreter.perform(batch(1)))
        assertEquals(1, store.inner.records.load(id)!!.hr.size)
    }

    @Test
    fun a_failed_flush_answers_persist_failed_and_stores_nothing() = runTest {
        val store = FlakyStore()
        val interpreter = StoreEffectInterpreter(store, id)
        interpreter.perform(RunEffect.Create(recording))
        store.failNext = true
        assertEquals(RunInput.PersistFailed(1), interpreter.perform(batch(1)))
        assertTrue(store.inner.records.load(id)!!.hr.isEmpty())
        assertEquals(RunInput.Persisted(2), interpreter.perform(batch(2)), "the next try goes through")
    }

    @Test
    fun the_other_effects_answer_nothing_and_a_failure_is_reported_not_thrown() = runTest {
        val store = FlakyStore()
        val failures = ArrayList<Pair<RunEffect.Store, Throwable>>()
        val interpreter = StoreEffectInterpreter(store, id, onFailure = { effect, e -> failures += effect to e })
        assertNull(interpreter.perform(RunEffect.Create(recording)))
        assertNull(interpreter.perform(RunEffect.Stop(at(100))))
        assertNull(interpreter.perform(RunEffect.BeginComputing))
        assertEquals(RecordingState.Computing, store.inner.records.load(id)!!.recording.state)
        assertTrue(failures.isEmpty())
        assertNull(interpreter.perform(RunEffect.Stop(at(101)))) // computing → stopping is not a legal step
        assertEquals(1, failures.size)
        assertEquals(RunEffect.Stop(at(101)), failures.single().first)
        assertTrue(failures.single().second is RecordingStoreError.IllegalTransition)
    }

    /** A failed stop is not lost: it is retried, in order, before anything after it reaches the store. */
    @Test
    fun a_failed_stop_is_retried_before_the_flush_and_computing_after_it() = runTest {
        val store = FlakyStore().apply { failStops = 1 }
        val interpreter = StoreEffectInterpreter(store, id)
        interpreter.perform(RunEffect.Create(recording))
        assertNull(interpreter.perform(RunEffect.Stop(at(100))))
        assertTrue(interpreter.pending, "the stop did not land")
        assertEquals(RunInput.Persisted(1), interpreter.perform(batch(1)), "the stop went through first, then the flush")
        assertFalse(interpreter.pending)
        interpreter.perform(RunEffect.BeginComputing)
        val stored = store.inner.records.load(id)!!.recording
        assertEquals(RecordingState.Computing, stored.state)
        assertEquals(at(100), stored.endUtc)
    }

    @Test
    fun while_a_stop_is_pending_a_flush_is_not_attempted_and_computing_waits_behind_it() = runTest {
        val store = FlakyStore().apply { failStops = 2 }
        val interpreter = StoreEffectInterpreter(store, id)
        interpreter.perform(RunEffect.Create(recording))
        interpreter.perform(RunEffect.Stop(at(100)))
        assertEquals(RunInput.PersistFailed(1), interpreter.perform(batch(1)), "the stop failed again: the batch stays with the reducer")
        assertTrue(store.inner.records.load(id)!!.hr.isEmpty())
        interpreter.perform(RunEffect.BeginComputing) // the stop lands now; computing right after it
        assertFalse(interpreter.pending)
        assertEquals(RecordingState.Computing, store.inner.records.load(id)!!.recording.state)
    }
}
