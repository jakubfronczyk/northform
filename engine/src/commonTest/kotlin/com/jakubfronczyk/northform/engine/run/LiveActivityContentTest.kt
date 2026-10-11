package com.jakubfronczyk.northform.engine.run

import kotlin.test.Test
import kotlin.test.assertEquals

/** D38: the lock screen shows the run screen's big number, the shown current pace (D22, D28), never split pace. */
class LiveActivityContentTest {
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
            val snapshot = RunSnapshot(RunPhase.Active, currentPace = row.current, splitPace = row.split)
            assertEquals(row.shown, LiveActivityContent.shownPace(snapshot), "$row")
        }
    }
}
