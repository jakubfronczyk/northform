package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.RecordingStoreError
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The runtime half of persistence/03: a database created at schema v1 and filled the way v1 wrote it
 * (raw SQL, not today's store) is migrated to the current version and read by today's store. With no
 * `.sqm` yet, `migrate(1 → 1)` is a no-op and this proves the harness; the first `.sqm` makes it a real
 * test without changing a line here. The build-time half is `verifySqlDelightMigration` (build.gradle.kts).
 */
class MigrationTest {
    private val t0 = Instant.parse("2026-10-10T06:00:00Z")
    private val id = RecordingId("v1-rec")

    @Test
    fun a_v1_database_migrates_to_the_current_schema_and_keeps_its_rows() = runTest {
        val driver = inMemoryDriver(schema = SchemaV1)
        try {
            val hr = listOf(HrSample(t0 + 1.seconds, 120), HrSample(t0 + 2.seconds, 121))
            val fixes = listOf(LocationFix(t0 + 1.seconds, 52.2297, 21.0122, 4.5, altitude = 101.25, stationary = false))
            driver.execute(null, "INSERT INTO recording(id, user_id, type, state, start_utc, tz_offset_s) VALUES ('v1-rec', 1, 'run', 'active', ${t0.toNanos()}, 7200)", 0)
            driver.execute(null, "UPDATE recording SET last_persisted_at = ${(t0 + 2.seconds).toNanos()} WHERE id = 'v1-rec'", 0)
            driver.execute(null, "INSERT INTO event(recording_id, seq, t, kind, source, split_index) VALUES ('v1-rec', 0, ${(t0 + 1.seconds).toNanos()}, 'split', 'auto', 1)", 0)
            driver.execute(null, "INSERT INTO stream_chunk(recording_id, kind, seq, t0, count, codec, payload) VALUES ('v1-rec', 'hr', 0, ${hr.first().t.toNanos()}, 2, 'hr.v1', ?)", 1) {
                bindBytes(0, StreamCodec.encodeHr(hr, hr.first().t))
            }
            driver.execute(null, "INSERT INTO stream_chunk(recording_id, kind, seq, t0, count, codec, payload) VALUES ('v1-rec', 'gps', 0, ${fixes.first().t.toNanos()}, 1, 'gps.v1', ?)", 1) {
                bindBytes(0, StreamCodec.encodeGps(fixes, fixes.first().t))
            }
            driver.execute(null, "INSERT INTO metric VALUES ('v1-rec', 'distance', 1, 1234.5, 0, 'm', 70.0, 180.0, 190, 60, '0.5,0.6,0.7,0.8,0.9')", 0)

            NorthformDb.Schema.migrate(driver, oldVersion = SchemaV1.version, newVersion = NorthformDb.Schema.version)

            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            val data = assertNotNull(store.load(id), "the v1 recording is still there after the migration")
            assertEquals(Recording(id = id, type = RecordingType.Run, state = RecordingState.Active, startUtc = t0, tzOffset = 7200), data.recording)
            assertEquals(hr, data.hr)
            assertEquals(fixes, data.fixes)
            assertEquals(listOf(RecordingEvent(t0 + 1.seconds, EventKind.Split(1), EventSource.Auto)), data.events)
            assertEquals(t0 + 2.seconds, data.lastPersistedAt)
            assertEquals(data.recording, store.unfinished(), "crash resume finds the v1 run after the upgrade")

            // v2 (1.sqm, engine/03): the migrated database takes a reliability row for the v1 run and reads it back.
            assertNull(store.reliability(id), "no log yet for a v1 run")
            val record = ReliabilityRecord(drops = 2, reconnects = 1, reconnectTimes = listOf(30.878.seconds), hrLost = 41.seconds, gpsLost = Duration.ZERO, recoveries = 1, bluetoothOff = 3)
            store.saveReliability(id, record)
            assertEquals(record, store.reliability(id))
            store.saveReliability(id, ReliabilityRecord.none)
            assertEquals(ReliabilityRecord.none, store.reliability(id), "a second write replaces the row")
            assertFailsWith<RecordingStoreError.NotFound> { store.saveReliability(RecordingId("nobody"), record) }
            store.delete(id)
            assertNull(store.reliability(id), "delete takes the log with the recording")
        } finally {
            driver.close()
        }
    }

    @Test
    fun a_v2_database_migrates_to_v3_and_its_stale_reliability_rows_are_recomputed_not_misread() = runTest {
        // v2 -> v3 (2.sqm, D31): the reliability cache gains `bluetooth_off`; a v2 row counted Bluetooth-off as a drop.
        val driver = inMemoryDriver(schema = SchemaV2)
        try {
            driver.execute(null, "INSERT INTO recording(id, user_id, type, state, start_utc, tz_offset_s) VALUES ('v1-rec', 1, 'run', 'saved', ${t0.toNanos()}, 0)", 0)
            driver.execute(null, "INSERT INTO event(recording_id, seq, t, kind, source, split_index) VALUES ('v1-rec', 0, ${(t0 + 1.seconds).toNanos()}, 'bluetoothOff', 'auto', NULL)", 0)
            driver.execute(null, "INSERT INTO reliability VALUES ('v1-rec', 1, 1, 0, '', 0, 0, 0)", 0)

            NorthformDb.Schema.migrate(driver, oldVersion = SchemaV2.version, newVersion = NorthformDb.Schema.version)

            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            assertNull(store.reliability(id), "the v2 row (a drop that was Bluetooth-off) is gone: recompute from the events")
            val data = assertNotNull(store.load(id))
            assertEquals(listOf(RecordingEvent(t0 + 1.seconds, EventKind.BluetoothOff, EventSource.Auto)), data.events)
            val record = ReliabilityRecord.of(data)
            assertEquals(ReliabilityRecord.none.copy(bluetoothOff = 1), record)
            store.saveReliability(id, record)
            assertEquals(record, store.reliability(id))
        } finally {
            driver.close()
        }
    }
}
