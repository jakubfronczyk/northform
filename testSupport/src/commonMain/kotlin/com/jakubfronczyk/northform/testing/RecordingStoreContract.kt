package com.jakubfronczyk.northform.testing

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.MetricId
import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.fromWire
import com.jakubfronczyk.northform.core.wire
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.RecordingStoreError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Tests/CoreTests/RecordingStoreContract.swift` — what every `RecordingStore` adapter must do, written
 * once against the port (the interface is the test surface). Each adapter's test class lists these
 * cases as its `@Test`s with a fresh store per case: the in-memory store and the SQLDelight store pass
 * the same file, so swapping one for the other in the composition root changes nothing above the port.
 */
class RecordingStoreContract(private val store: RecordingStore) {
    private val t0 = Instant.parse("2026-10-10T06:00:00Z")
    private val id = RecordingId("rec-1")
    private val other = RecordingId("rec-2")

    private fun recording(id: RecordingId = this.id, state: RecordingState = RecordingState.Active) =
        Recording(id = id, type = RecordingType.Run, state = state, startUtc = t0, tzOffset = 7200)

    private fun hr(seconds: Int, bpm: Int) = HrSample(t0 + seconds.seconds, bpm)

    /** The full fix: every optional field present, sub-second time. */
    private val fullFix = LocationFix(
        t = t0 + 1.seconds + 250.milliseconds + 7.microseconds, latitude = 52.2297, longitude = 21.0122, horizontalAccuracy = 4.5,
        altitude = 101.25, speed = 2.75, stationary = true, speedAccuracy = 0.5, course = 180.0, courseAccuracy = 10.0, verticalAccuracy = 3.0,
    )

    /** The bare fix: only what the adapter always sends (D102: `stationary` unknown). */
    private val bareFix = LocationFix(t = t0 + 2.seconds, latitude = -33.8688, longitude = 151.2093, horizontalAccuracy = 0.0)

    suspend fun create_then_load_round_trips_the_recording_with_no_data() {
        store.create(recording())
        val data = assertNotNull(store.load(id))
        assertEquals(recording(), data.recording)
        assertEquals(emptyList(), data.hr)
        assertEquals(emptyList(), data.fixes)
        assertEquals(emptyList(), data.events)
        assertNull(data.lastPersistedAt)
        assertNull(store.load(other), "an unknown id loads nothing")
    }

    suspend fun create_refuses_a_duplicate_and_a_non_active_recording() {
        store.create(recording())
        assertEquals(RecordingStoreError.AlreadyExists(id), assertFailsWith<RecordingStoreError.AlreadyExists> { store.create(recording()) })
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.create(recording(other, RecordingState.Stopping)) }
        assertNull(store.load(other), "nothing was written")
    }

    suspend fun flush_appends_in_order_and_round_trips_every_field() {
        store.create(recording())
        store.flush(FlushBatch(id, hr = listOf(hr(1, 120), hr(2, 121)), fixes = listOf(fullFix), events = listOf(RecordingEvent(t0 + 1.seconds, EventKind.Pause, EventSource.Manual)), persistedAt = t0 + 2.seconds))
        store.flush(FlushBatch(id, hr = listOf(hr(3, 65535)), fixes = listOf(bareFix), events = listOf(RecordingEvent(t0 + 3.seconds, EventKind.Split(3), EventSource.Auto)), persistedAt = t0 + 4.seconds))
        store.flush(FlushBatch(id, persistedAt = t0 + 6.seconds)) // a heartbeat alone

        val data = assertNotNull(store.load(id))
        assertEquals(listOf(hr(1, 120), hr(2, 121), hr(3, 65535)), data.hr)
        assertEquals(listOf(fullFix, bareFix), data.fixes)
        assertEquals(
            listOf(RecordingEvent(t0 + 1.seconds, EventKind.Pause, EventSource.Manual), RecordingEvent(t0 + 3.seconds, EventKind.Split(3), EventSource.Auto)),
            data.events,
        )
        assertEquals(t0 + 6.seconds, data.lastPersistedAt)
    }

    suspend fun every_event_kind_round_trips() {
        store.create(recording())
        val kinds = listOf(
            EventKind.Pause, EventKind.Resume, EventKind.Split(12), EventKind.HrLost, EventKind.HrBack, EventKind.GpsLost, EventKind.GpsBack,
            EventKind.SensorLost, EventKind.SensorReconnected, EventKind.BluetoothOff, EventKind.CooldownEnd, EventKind.CrashRecovered,
        )
        val events = kinds.mapIndexed { i, k -> RecordingEvent(t0 + i.seconds, k, if (i % 2 == 0) EventSource.Auto else EventSource.Manual) }
        store.flush(FlushBatch(id, events = events, persistedAt = t0 + 20.seconds))
        assertEquals(events, assertNotNull(store.load(id)).events)
        // The one decoder every store and the backup read through: each kind's stored name comes back as that kind.
        for (k in kinds + EventKind.Stop) assertEquals(k, EventKind.fromWire(k.wire, (k as? EventKind.Split)?.index), k.wire)
    }

    suspend fun flush_needs_a_recording_in_active_or_stopping() {
        assertEquals(RecordingStoreError.NotFound(id), assertFailsWith<RecordingStoreError.NotFound> { store.flush(FlushBatch(id, persistedAt = t0)) })
        store.create(recording())
        store.stop(id, t0 + 10.seconds)
        store.flush(FlushBatch(id, hr = listOf(hr(11, 110)), persistedAt = t0 + 11.seconds)) // the cool-down flushes
        store.beginComputing(id)
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.flush(FlushBatch(id, hr = listOf(hr(12, 100)), persistedAt = t0 + 12.seconds)) }
        val data = assertNotNull(store.load(id))
        assertEquals(listOf(hr(11, 110)), data.hr, "the refused flush wrote nothing")
        assertEquals(t0 + 11.seconds, data.lastPersistedAt)
    }

    suspend fun stop_writes_end_and_the_stop_event_together() {
        store.create(recording())
        store.flush(FlushBatch(id, events = listOf(RecordingEvent(t0 + 5.seconds, EventKind.Pause, EventSource.Manual)), persistedAt = t0 + 5.seconds))
        store.stop(id, t0 + 10.seconds)
        val data = assertNotNull(store.load(id))
        assertEquals(RecordingState.Stopping, data.recording.state)
        assertEquals(t0 + 10.seconds, data.recording.endUtc)
        assertEquals(RecordingEvent(t0 + 10.seconds, EventKind.Stop, EventSource.Manual), data.events.last())
        assertEquals(2, data.events.size)
        assertEquals(
            RecordingStoreError.IllegalTransition(RecordingState.Stopping, RecordingState.Stopping),
            assertFailsWith<RecordingStoreError.IllegalTransition> { store.stop(id, t0 + 11.seconds) },
        )
    }

    suspend fun the_state_machine_allows_one_step_at_a_time() {
        store.create(recording())
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.beginComputing(id) }
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.saveSummary(id, null, null, null, emptyList()) }
        store.stop(id, t0 + 10.seconds)
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.saveSummary(id, null, null, null, emptyList()) }
        store.beginComputing(id)
        assertEquals(RecordingState.Computing, assertNotNull(store.load(id)).recording.state)
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.beginComputing(id) }
        assertFailsWith<RecordingStoreError.NotFound> { store.beginComputing(other) }
    }

    suspend fun save_summary_finishes_the_recording() {
        store.create(recording())
        store.stop(id, t0 + 10.seconds)
        store.beginComputing(id)
        val profile = Profile(birthDate = Instant.parse("1990-05-01T00:00:00Z"), sex = Sex.Male, weightKg = 72.0, heightCm = 180.0, hrMax = 187, hrRest = 52, zones = HRZones.standard)
        store.saveSummary(id, RunTag.Tempo, 7, "felt smooth", listOf(MetricResult(MetricId("trimp"), 1, 118.5, profile)))
        val saved = assertNotNull(store.load(id)).recording
        assertEquals(recording().copy(state = RecordingState.Saved, endUtc = t0 + 10.seconds, tag = RunTag.Tempo, rpe = 7, notes = "felt smooth"), saved)
        assertNull(store.unfinished(), "a saved recording is finished")
        assertFailsWith<RecordingStoreError.IllegalTransition> { store.saveSummary(id, null, null, null, emptyList()) }
    }

    suspend fun unfinished_is_the_recording_a_crash_left_behind() {
        assertNull(store.unfinished())
        store.create(recording())
        assertEquals(recording(), store.unfinished())
        store.stop(id, t0 + 10.seconds)
        assertEquals(RecordingState.Stopping, store.unfinished()?.state)
        store.beginComputing(id)
        assertEquals(RecordingState.Computing, store.unfinished()?.state)
    }

    suspend fun delete_removes_everything_and_an_unknown_id_is_an_error() {
        store.create(recording())
        store.flush(FlushBatch(id, hr = listOf(hr(1, 120)), fixes = listOf(bareFix), events = listOf(RecordingEvent(t0 + 1.seconds, EventKind.Pause, EventSource.Manual)), persistedAt = t0 + 1.seconds))
        store.delete(id)
        assertNull(store.load(id))
        assertNull(store.unfinished())
        assertEquals(RecordingStoreError.NotFound(id), assertFailsWith<RecordingStoreError.NotFound> { store.delete(id) })
        store.create(recording()) // the id is free again, with no data left over
        assertEquals(emptyList(), assertNotNull(store.load(id)).hr)
    }
}
