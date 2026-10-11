package com.jakubfronczyk.northform.core.reliability

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.RecordingData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** R29 as a table: the record is a pure function of the stored events, so every rule is one row here. */
class ReliabilityRecordTest {
    private val start = Instant.parse("2026-10-10T07:00:00Z")
    private fun at(s: Int) = start + s.seconds

    private fun data(vararg events: Pair<Int, EventKind>, heartbeat: Int? = 600, end: Int? = null) = RecordingData(
        recording = Recording(RecordingId("r"), type = RecordingType.Run, state = RecordingState.Computing, startUtc = start, tzOffset = 0, endUtc = end?.let(::at)),
        hr = emptyList(), fixes = emptyList(),
        events = events.map { (s, kind) -> RecordingEvent(at(s), kind, EventSource.Auto) },
        lastPersistedAt = heartbeat?.let(::at),
    )

    @Test
    fun a_clean_run_has_an_empty_record() {
        assertEquals(ReliabilityRecord.none, ReliabilityRecord.of(data(10 to EventKind.Split(1), 20 to EventKind.Pause, 30 to EventKind.Resume, 40 to EventKind.Stop, 100 to EventKind.CooldownEnd)))
    }

    @Test
    fun a_drop_and_its_reconnect_count_once_with_the_time_between_them() {
        val record = ReliabilityRecord.of(data(100 to EventKind.SensorLost, 130 to EventKind.SensorReconnected))
        assertEquals(ReliabilityRecord(drops = 1, reconnects = 1, reconnectTimes = listOf(30.seconds), hrLost = Duration.ZERO, gpsLost = Duration.ZERO, crashes = 0), record)
        assertEquals(30.seconds, record.reconnectTotal)
        assertEquals(30.seconds, record.reconnectLongest)
    }

    @Test
    fun a_drop_never_reconnected_counts_as_a_drop_with_no_reconnect_time() {
        val record = ReliabilityRecord.of(data(100 to EventKind.SensorLost, 110 to EventKind.SensorReconnected, 200 to EventKind.SensorLost))
        assertEquals(2, record.drops)
        assertEquals(1, record.reconnects)
        assertEquals(listOf(10.seconds), record.reconnectTimes)
    }

    @Test
    fun a_reconnect_without_a_drop_is_counted_but_has_no_time() {
        // Crash resume rebuilds through the reducer and can replay a Connected after the gap without a stored Lost.
        val record = ReliabilityRecord.of(data(100 to EventKind.SensorReconnected))
        assertEquals(0, record.drops)
        assertEquals(1, record.reconnects)
        assertEquals(emptyList(), record.reconnectTimes)
        assertEquals(Duration.ZERO, record.reconnectLongest)
    }

    @Test
    fun bluetooth_off_is_counted_apart_and_is_never_a_drop() {
        // D31: radio off at 100, link back at 130 — a reconnect, but no drop opened, so no reconnect time.
        val record = ReliabilityRecord.of(data(100 to EventKind.BluetoothOff, 130 to EventKind.SensorReconnected, 200 to EventKind.SensorLost, 210 to EventKind.SensorReconnected))
        assertEquals(1, record.bluetoothOff)
        assertEquals(1, record.drops)
        assertEquals(2, record.reconnects)
        assertEquals(listOf(10.seconds), record.reconnectTimes)
    }

    @Test
    fun lost_seconds_sum_every_lost_to_back_interval_per_signal() {
        val record = ReliabilityRecord.of(data(
            10 to EventKind.HrLost, 15 to EventKind.HrBack, 40 to EventKind.HrLost, 52 to EventKind.HrBack,
            20 to EventKind.GpsLost, 31 to EventKind.GpsBack,
        ))
        assertEquals(17.seconds, record.hrLost)
        assertEquals(11.seconds, record.gpsLost)
    }

    @Test
    fun an_open_loss_closes_at_the_heartbeat_the_latest_moment_the_store_knows() {
        val record = ReliabilityRecord.of(data(500 to EventKind.HrLost, 580 to EventKind.GpsLost, heartbeat = 600))
        assertEquals(100.seconds, record.hrLost)
        assertEquals(20.seconds, record.gpsLost)
    }

    @Test
    fun without_a_heartbeat_an_open_loss_closes_at_the_stop_then_at_the_last_event() {
        assertEquals(40.seconds, ReliabilityRecord.of(data(60 to EventKind.HrLost, heartbeat = null, end = 100)).hrLost)
        assertEquals(30.seconds, ReliabilityRecord.of(data(60 to EventKind.GpsLost, 90 to EventKind.Split(1), heartbeat = null)).gpsLost)
    }

    @Test
    fun a_back_without_a_loss_and_a_second_loss_while_lost_change_nothing() {
        val record = ReliabilityRecord.of(data(5 to EventKind.HrBack, 10 to EventKind.HrLost, 12 to EventKind.HrLost, 20 to EventKind.HrBack))
        assertEquals(10.seconds, record.hrLost)
    }

    @Test
    fun every_crash_recovered_event_is_a_crash() {
        assertEquals(2, ReliabilityRecord.of(data(100 to EventKind.CrashRecovered, 300 to EventKind.CrashRecovered)).crashes)
    }

    @Test
    fun a_crash_after_close_at_still_counts() {
        // D40/D32: Save after a crash marks it at the answer (300 s), after the run's end at its heartbeat (110 s).
        val saved = data(110 to EventKind.Stop, 300 to EventKind.CrashRecovered, heartbeat = 110, end = 110)
        assertEquals(1, ReliabilityRecord.of(saved, closeAt = at(110)).crashes)
    }

    @Test
    fun with_close_at_the_cool_down_counts_nothing() {
        // HR lost at 100, stop at 110, HR back at 140 in the cool-down: the effort lost 10 s, not 40 s.
        val gap = data(100 to EventKind.HrLost, 110 to EventKind.Stop, 140 to EventKind.HrBack, heartbeat = 170, end = 110)
        assertEquals(10.seconds, ReliabilityRecord.of(gap, closeAt = at(110)).hrLost)
        // A drop in the cool-down is not the effort's either.
        val drop = data(110 to EventKind.Stop, 130 to EventKind.SensorLost, 150 to EventKind.SensorReconnected, heartbeat = 170, end = 110)
        assertEquals(ReliabilityRecord.none, ReliabilityRecord.of(drop, closeAt = at(110)))
        // Without closeAt the whole stored run counts.
        assertEquals(40.seconds, ReliabilityRecord.of(gap).hrLost)
    }
}
