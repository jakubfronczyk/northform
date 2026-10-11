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
import com.jakubfronczyk.northform.core.ports.RecordingData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The `northform-backup` v1 file (persistence/04, D26): bit for bit both ways, readable, versioned. */
class BackupCodecTest {
    private val t0 = Instant.parse("2026-10-10T06:00:00.123456789Z")
    private val exportedAt = Instant.parse("2026-10-11T12:00:00Z")

    private val profile = Profile(
        birthDate = Instant.parse("1990-05-04T00:00:00Z"), sex = Sex.Female, weightKg = 0.1 + 0.2, heightCm = 1.0 / 3,
        hrMax = 190, hrRest = 45, zones = HRZones(listOf(0.5, 0.55, 2.0 / 3, 0.8, 0.9)),
    )

    private val everyKind = listOf(
        EventKind.Pause, EventKind.Resume, EventKind.Split(3), EventKind.HrLost, EventKind.HrBack, EventKind.GpsLost, EventKind.GpsBack,
        EventKind.SensorLost, EventKind.SensorReconnected, EventKind.BluetoothOff, EventKind.Stop, EventKind.CooldownEnd, EventKind.CrashRecovered,
    )

    /** A saved run with everything set and the doubles that break naive formatting. */
    private val saved = BackupRecording(
        data = RecordingData(
            recording = Recording(
                id = RecordingId("rec-saved"), type = RecordingType.Run, tag = RunTag.Tempo, state = RecordingState.Saved, startUtc = t0, tzOffset = -3600,
                endUtc = t0 + 3600.seconds + 1.nanoseconds, rpe = 7, notes = "quotes \" backslash \\ newline \n unicode ż",
            ),
            hr = listOf(HrSample(t0 - 1.seconds, 0), HrSample(t0 + 1.seconds + 1.nanoseconds, 150), HrSample(t0 + 3600.seconds, 65535)),
            fixes = listOf(
                LocationFix(t0, 1.0 / 3, -2.0 / 3, 5.0),
                LocationFix(t0 + 2.seconds + 250.milliseconds + 7.microseconds, 0.1 + 0.2, Double.MIN_VALUE, Double.MAX_VALUE, altitude = -0.0, speed = Double.NaN, stationary = false),
                LocationFix(t0 + 4.seconds, 0.0, 1e21, 1e-7, stationary = true, speedAccuracy = 1.0, course = 359.9, courseAccuracy = 0.0, verticalAccuracy = Double.POSITIVE_INFINITY),
            ),
            events = everyKind.mapIndexed { i, k -> RecordingEvent(t0 + i.seconds, k, if (i % 2 == 0) EventSource.Auto else EventSource.Manual) },
            lastPersistedAt = t0 + 3599.seconds,
        ),
        metrics = listOf(
            MetricResult(MetricId("distance_m"), 1, 12345.678, profile),
            MetricResult(MetricId("trimp"), 2, 1.0 / 7, profile),
        ),
    )

    /** A run a crash left behind: nothing optional set, no data yet. */
    private val unfinished = BackupRecording(
        data = RecordingData(
            recording = Recording(id = RecordingId("rec-active"), type = RecordingType.Walk, state = RecordingState.Active, startUtc = t0 + 86400.seconds, tzOffset = 0),
            hr = emptyList(), fixes = emptyList(), events = emptyList(), lastPersistedAt = null,
        ),
        metrics = emptyList(),
    )

    private val backup = Backup(exportedAt, listOf(saved, unfinished))

    @Test
    fun a_backup_round_trips_bit_for_bit() {
        val text = BackupCodec.encode(backup)
        val decoded = BackupCodec.decode(text)
        assertEquals(backup, decoded) // data-class equality already treats NaN as itself and -0.0 as distinct from 0.0
        for ((a, b) in saved.data.fixes.zip(decoded.recordings[0].data.fixes)) {
            assertEquals(a.latitude.toRawBits(), b.latitude.toRawBits())
            assertEquals(a.longitude.toRawBits(), b.longitude.toRawBits())
            assertEquals(a.horizontalAccuracy.toRawBits(), b.horizontalAccuracy.toRawBits())
            assertEquals(a.altitude?.toRawBits(), b.altitude?.toRawBits())
            assertEquals(a.speed?.toRawBits(), b.speed?.toRawBits())
            assertEquals(a.verticalAccuracy?.toRawBits(), b.verticalAccuracy?.toRawBits())
        }
        assertEquals(text, BackupCodec.encode(decoded), "the writer is deterministic")
    }

    @Test
    fun the_file_is_one_readable_versioned_document() {
        val text = BackupCodec.encode(backup)
        assertTrue(text.startsWith("{\n  \"format\": \"northform-backup\",\n  \"version\": 1,\n  \"exportedAt\": \"2026-10-11T12:00:00Z\",\n"), text.take(200))
        assertTrue("\n      \"id\": \"rec-saved\",\n" in text, "a recording's fields are named, one per line")
        assertTrue("\n        [-1000000000,0],\n        [1000000001,150],\n        [3600000000000,65535]\n" in text, "a stream is one row per line, offsets in ns from startUtc")
        assertTrue("\"kind\": \"split\", " !in text && "\"splitIndex\": 3" in text, "a split carries its index")
        assertTrue("\"recordings\": []" in BackupCodec.encode(Backup(exportedAt, emptyList())), "an empty database is still a valid backup")
    }

    @Test
    fun a_foreign_newer_or_broken_file_is_refused_with_a_reason() {
        assertEquals(BackupError.NotABackup("gpx"), assertFailsWith<BackupError.NotABackup> { BackupCodec.decode("""{"format":"gpx","version":1}""") })
        assertEquals(BackupError.NotABackup(null), assertFailsWith<BackupError.NotABackup> { BackupCodec.decode("""{"k":"meta"}""") })
        assertEquals(BackupError.UnsupportedVersion(2), assertFailsWith<BackupError.UnsupportedVersion> { BackupCodec.decode("""{"format":"northform-backup","version":2,"recordings":[]}""") })
        assertFailsWith<BackupError.Malformed> { BackupCodec.decode("not json at all") }
        assertFailsWith<BackupError.Malformed> { BackupCodec.decode("""{"format":"northform-backup","version":1,"exportedAt":"2026-10-11T12:00:00Z"}""") }.also {
            assertEquals("recordings", it.path)
        }
        val shortRow = BackupCodec.encode(backup).replace("[1000000001,150]", "[1000000001]")
        assertEquals("recordings[0].hr[1]", assertFailsWith<BackupError.Malformed> { BackupCodec.decode(shortRow) }.path)
    }
}
