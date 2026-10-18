package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.metrics.Zone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** D38: the lock screen shows the run screen's big number, the shown current pace (D22, D28), never split pace. */
class LiveActivityContentTest {
    private val startedAt = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun the_pace_is_the_shown_current_pace_rounded_to_5_s_not_the_split_pace() {
        data class Row(val current: Double?, val split: Double?, val shown: Int?)
        val table = listOf(
            Row(current = 304.0, split = 312.0, shown = 305), // the split pace is ignored
            Row(current = 302.4, split = 312.0, shown = 300),
            Row(current = 302.5, split = 290.0, shown = 305), // ties away from zero, like `RunFormat.pace`
            Row(current = null, split = 312.0, shown = null), // no current pace: nothing, not the split
        )
        for (row in table) {
            val snapshot = RunSnapshot(RunPhase.Active, startedAt = startedAt, currentPace = row.current, splitPace = row.split)
            assertEquals(row.shown, LiveActivityContent.of(snapshot)?.paceSecPerKm, "$row")
        }
    }

    @Test
    fun every_field_comes_from_the_snapshot() {
        val snapshot = RunSnapshot(
            RunPhase.Paused(since = startedAt + 60.seconds), startedAt = startedAt, totalTime = 90.5.seconds, distance = 250.0,
            currentPace = null, hrNow = 141, hrLost = false, gpsLost = true, zone = Zone(3, 0.6, 0.7), type = RecordingType.Walk,
        )
        assertEquals(
            LiveActivityContent(
                LiveActivityPhase.Paused, startedAt.toEpochMilliseconds(), 90, 250.0, null, 141, hrLost = false, gpsLost = true,
                zoneNumber = 3, zoneName = "Moderate", typeName = "Walk",
            ),
            LiveActivityContent.of(snapshot),
        )
    }

    @Test
    fun the_phase_is_active_paused_or_done_and_there_is_nothing_before_the_start() {
        assertNull(LiveActivityContent.of(RunSnapshot(RunPhase.Gate)))
        assertNull(LiveActivityContent.of(RunSnapshot(RunPhase.Countdown(endsAt = startedAt))))
        assertEquals(LiveActivityPhase.Active, LiveActivityContent.of(RunSnapshot(RunPhase.Active, startedAt = startedAt))?.phase)
        assertEquals(LiveActivityPhase.Done, LiveActivityContent.of(RunSnapshot(RunPhase.Cooldown(until = startedAt + 60.seconds), startedAt = startedAt))?.phase)
        assertEquals(LiveActivityPhase.Done, LiveActivityContent.of(RunSnapshot(RunPhase.Done, startedAt = startedAt))?.phase)
    }
}
