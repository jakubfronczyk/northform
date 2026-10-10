package com.jakubfronczyk.northform.persistence

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
import com.jakubfronczyk.northform.core.ports.FlushBatch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `exportAll` sees what the port wrote: every recording, in start order, in any state, with the
 * metrics `saveSummary` stored (the only way metrics leave the database today). Round trip through
 * the codec on top, so export → file → parse == export.
 */
class SqlRecordingStoreExportTest {
    private val t0 = Instant.parse("2026-10-10T06:00:00Z")
    private val profile = Profile(birthDate = Instant.parse("1990-05-04T00:00:00Z"), sex = Sex.Male, weightKg = 72.5, heightCm = 181.0, hrMax = 188, hrRest = 52, zones = HRZones(listOf(0.5, 0.6, 0.7, 0.8, 0.9)))

    @Test
    fun export_all_has_every_recording_with_its_data_and_metrics() = runTest {
        val driver = inMemoryDriver()
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            val later = RecordingId("later-but-created-first")
            val earlier = RecordingId("earlier")
            store.create(Recording(id = later, type = RecordingType.Walk, state = RecordingState.Active, startUtc = t0 + 86400.seconds, tzOffset = 0))
            store.create(Recording(id = earlier, type = RecordingType.Run, state = RecordingState.Active, startUtc = t0, tzOffset = 7200))
            store.flush(FlushBatch(earlier, hr = listOf(HrSample(t0 + 1.seconds, 120)), fixes = listOf(LocationFix(t0 + 1.seconds, 52.2297, 21.0122, 4.5, altitude = 101.25)), events = listOf(RecordingEvent(t0 + 1.seconds, EventKind.Split(1), EventSource.Auto)), persistedAt = t0 + 2.seconds))
            store.stop(earlier, t0 + 10.seconds)
            store.beginComputing(earlier)
            val metrics = listOf(MetricResult(MetricId("distance_m"), 1, 1234.5, profile), MetricResult(MetricId("avg_hr"), 1, 120.0, profile))
            store.saveSummary(earlier, RunTag.Easy, 5, "notes", metrics)
            store.flush(FlushBatch(later, hr = listOf(HrSample(t0 + 86401.seconds, 90)), persistedAt = t0 + 86402.seconds))

            val backup = store.exportAll(exportedAt = t0 + (2 * 86400).seconds)

            assertEquals(t0 + (2 * 86400).seconds, backup.exportedAt)
            assertEquals(listOf(earlier, later), backup.recordings.map { it.data.recording.id }, "start order, not insertion order")
            assertEquals(BackupRecording(assertNotNull(store.load(earlier)), metrics.sortedBy { it.metricId.value }), backup.recordings[0], "metrics come out in metric_id order")
            assertEquals(BackupRecording(assertNotNull(store.load(later)), emptyList()), backup.recordings[1], "an unfinished run is backed up too")
            assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup)))
        } finally {
            driver.close()
        }
    }

    @Test
    fun an_empty_database_exports_an_empty_backup() = runTest {
        val driver = inMemoryDriver()
        try {
            assertEquals(Backup(t0, emptyList()), SqlRecordingStore(driver, io = EmptyCoroutineContext).exportAll(t0))
        } finally {
            driver.close()
        }
    }
}
