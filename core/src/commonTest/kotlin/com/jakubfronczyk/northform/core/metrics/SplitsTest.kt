package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SplitsTest {
    private val t0 = Instant.fromEpochSeconds(1_767_225_600)
    private fun at(s: Double) = t0 + s.seconds
    private fun at(s: Int) = at(s.toDouble())
    private fun event(s: Int, kind: EventKind, source: EventSource = EventSource.Auto) = RecordingEvent(at(s), kind, source)
    private fun fix(s: Int, altitude: Double) = LocationFix(at(s), 52.5, 13.4, horizontalAccuracy = 5.0, altitude = altitude)

    // ── D121: the shared interpolation ────────────────────────────────────────────────────────

    @Test
    fun a_mark_inside_a_segment_is_reached_linearly_in_time() {
        // 980 → 1030 m over 10 s: the 1000 m mark is 40 % along, at +4 s
        assertEquals(at(14), Splits.markReachedAt(mark = 1000.0, before = 980.0, metres = 50.0, from = at(10), to = at(20)))
        assertEquals(at(10), Splits.markReachedAt(1000.0, before = 1000.0, metres = 50.0, from = at(10), to = at(20)), "a mark at the segment's start")
        assertEquals(at(20), Splits.markReachedAt(1000.0, before = 950.0, metres = 50.0, from = at(10), to = at(20)), "a mark at its end")
    }

    // ── The shared rules ──────────────────────────────────────────────────────────────────────

    @Test
    fun pace_is_seconds_per_km_from_50_m_and_the_mean_rounds_half_away_from_zero() {
        assertEquals(300.0, Splits.pace(150.seconds, 500.0))
        assertEquals(1000.0, Splits.pace(50.seconds, 50.0), "exactly the bar")
        assertNull(Splits.pace(49.seconds, 49.9), "under the bar (D102)")
        assertEquals(145, HeartRate.mean(sum = 289, count = 2), "144.5 → 145")
        assertEquals(145, HeartRate.mean(listOf(HrSample(at(0), 140), HrSample(at(1), 149))))
        assertNull(HeartRate.mean(0, 0))
    }

    // ── Pauses ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun pauses_come_from_the_events_and_an_open_one_closes_at_stop() {
        val events = listOf(
            event(10, EventKind.Pause, EventSource.Manual), event(20, EventKind.Resume, EventSource.Manual),
            event(30, EventKind.HrLost), event(40, EventKind.Pause, EventSource.Manual),
        )
        assertEquals(listOf(Pause(at(10), at(20)), Pause(at(40), at(50))), Pauses.intervals(events, stoppedAt = at(50)))
        assertEquals(20.seconds, Pauses.activeBetween(Pauses.intervals(events, at(50)), at(15), at(45)), "30 s wall − (5 s + 5 s) paused")
    }

    // ── The table ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun rows_follow_the_stored_marks_with_a_partial_last_km_to_the_stop() {
        // 2 marks at 300 s and 620 s (a 20 s pause inside km 2), stop at 800 s with 2 350 m counted
        val events = listOf(
            event(300, EventKind.Split(1)),
            event(400, EventKind.Pause, EventSource.Manual), event(420, EventKind.Resume, EventSource.Manual),
            event(620, EventKind.Split(2)),
        )
        val hr = (0 until 800).map { HrSample(at(it), if (it < 300) 140 else if (it < 620) 150 else 160) } + HrSample(at(650), 250) // one invalid, dropped
        val fixes = listOf(fix(0, 100.0), fix(100, 110.0), fix(350, 105.0), fix(700, 108.0))
        val rows = Splits.table(Splits.marks(events), startedAt = at(0), stoppedAt = at(800), totalDistance = 2350.0, pauses = Pauses.intervals(events, at(800)), hr = hr, fixes = fixes)

        assertEquals(listOf(1, 2, 3), rows.map { it.index })
        assertEquals(listOf(1000.0, 1000.0, 350.0), rows.map { it.distance })
        assertEquals(listOf(300.seconds, 300.seconds, 180.seconds), rows.map { it.time }, "km 2 is 320 s wall − 20 s paused")
        assertEquals(listOf(300.0, 300.0, 180.0 / 350 * 1000), rows.map { it.pace })
        assertEquals(listOf(140, 150, 160), rows.map { it.avgHr })
        assertEquals(listOf(10.0, 0.0, 3.0), rows.map { it.ascent })
        assertEquals(listOf(0.0, 5.0, 0.0), rows.map { it.descent })
        assertEquals(listOf(false, false, true), rows.map { it.partial })
        assertEquals(at(300) to at(620), rows[1].startAt to rows[1].endAt)
    }

    @Test
    fun a_short_partial_km_has_no_pace_and_no_remainder_means_no_partial_row() {
        val rows = Splits.table(listOf(Splits.Mark(1, at(300))), at(0), at(310), totalDistance = 1040.0, pauses = emptyList(), hr = emptyList(), fixes = emptyList())
        assertEquals(2, rows.size)
        assertNull(rows[1].pace, "40 m is under the 50 m bar (D102)")
        assertNull(rows[1].avgHr, "no samples")
        assertEquals(1, Splits.table(listOf(Splits.Mark(1, at(300))), at(0), at(300), totalDistance = 1000.0, pauses = emptyList(), hr = emptyList(), fixes = emptyList()).size)
    }

    @Test
    fun a_run_under_one_km_is_one_partial_row_and_no_distance_is_no_row() {
        val one = Splits.table(emptyList(), at(0), at(100), totalDistance = 420.0, pauses = emptyList(), hr = emptyList(), fixes = emptyList())
        assertEquals(listOf(Splits.Row(1, at(0), at(100), 420.0, 100.seconds, 100.0 / 420 * 1000, null, 0.0, 0.0, partial = true)), one)
        assertEquals(emptyList(), Splits.table(emptyList(), at(0), at(100), totalDistance = 0.0, pauses = emptyList(), hr = emptyList(), fixes = emptyList()))
    }
}
