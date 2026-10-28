package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.seedComputing
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.coroutines.cancellation.CancellationException

/** history-screen/01: one damaged recording never fails the list (History reads everything). */
class StoredSavedRecordingsTest {
    private fun data(id: String, startS: Int) = RecordingData(
        recording = Recording(id = RecordingId(id), type = RecordingType.Run, state = RecordingState.Computing, startUtc = at(startS), tzOffset = 0, endUtc = at(startS + 60)),
        hr = (1..60).map { HrSample(at(startS + it), 150) },
        fixes = (1..60).map { fix(startS + it, north = it * 3.0) },
        events = listOf(RecordingEvent(at(startS + 60), EventKind.CooldownEnd, EventSource.Auto)),
        lastPersistedAt = at(startS + 60),
    )

    @Test
    fun a_recording_whose_numbers_cannot_be_read_is_left_out_and_the_rest_still_show() = runTest {
        val store = InMemoryRecordingStore()
        for ((id, start) in listOf("old" to 0, "damaged" to 1000, "new" to 2000)) {
            store.seedComputing(data(id, start))
            store.saveSummary(RecordingId(id), tag = null, rpe = null, notes = null, metrics = emptyList()) // saved before the headline rows existed
        }
        val damaged = object : RecordingStore by store {
            override suspend fun load(id: RecordingId): RecordingData? = if (id.value == "damaged") error("corrupt chunk") else store.load(id)
        }
        val left = ArrayList<String>()

        val saved = StoredSavedRecordings(store, damaged, log = { left += it }).latest(10)

        assertEquals(listOf("new", "old"), saved.map { it.recording.id.value })
        assertEquals(1, left.count { "damaged" in it }, "logged: $left")
    }

    /** A cancelled read is the caller's to see, never a recording "left out". */
    @Test
    fun a_cancelled_read_is_rethrown_not_logged_as_a_damaged_recording() = runTest {
        val store = InMemoryRecordingStore()
        store.seedComputing(data("a", 0))
        store.saveSummary(RecordingId("a"), tag = null, rpe = null, notes = null, metrics = emptyList())
        val cancelled = object : RecordingStore by store {
            override suspend fun load(id: RecordingId): RecordingData? = throw CancellationException("screen gone")
        }
        val left = ArrayList<String>()

        assertFailsWith<CancellationException> { StoredSavedRecordings(store, cancelled, log = { left += it }).latest(10) }
        assertEquals(emptyList(), left)
    }
}
