package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord
import com.jakubfronczyk.northform.testing.RunHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * engine/03 "done when": the bundled recordings replayed through the harness give the counts the log shows.
 * Three fixtures script exactly one drop: run-1-park `lost` 1708.021 s → `connected` 1738.899 s (lines 3139 → 3171),
 * run-2-walk `lost` 1047.832 s → `connected` 1061.767 s (lines 2116 → 2135), straight-run-3min `lost` 60.2 s →
 * `connected` 75.2 s (lines 124 → 140). The lost seconds are pinned from the Kotlin replay (deterministic): the
 * reducer's 5 s HR rule covers each drop plus the quiet stretches of a real sensor; the real runs' GPS loss is
 * their stretches of fixes worse than 50 m or 10 s apart (R26), unrelated to the drop.
 */
class ReliabilityPinTest {

    private fun play(name: String) = bundled(name).let { RunHarness.play(it.meta.start, it.runInputs).stored }

    @Test
    fun one_scripted_drop_gives_one_drop_and_one_reconnect_timed_between_the_stored_events() {
        for (name in listOf("run-1-park", "run-2-walk", "straight-run-3min")) {
            val stored = play(name)
            val record = ReliabilityRecord.of(stored)
            val lost = stored.events.single { it.kind == EventKind.SensorLost }
            val back = stored.events.single { it.kind == EventKind.SensorReconnected }
            assertEquals(1, record.drops, name)
            assertEquals(1, record.reconnects, name)
            assertEquals(listOf(back.t - lost.t), record.reconnectTimes, "$name: the reconnect time is the span between the two stored events")
            assertEquals(0, record.crashes, name)
        }
    }

    @Test
    fun the_records_stay_put() {
        val expected = mapOf(
            "run-1-park" to ReliabilityRecord(drops = 1, reconnects = 1, reconnectTimes = listOf(30.878.seconds), hrLost = 84.189.seconds, gpsLost = 439.734.seconds, crashes = 0),
            "run-2-walk" to ReliabilityRecord(drops = 1, reconnects = 1, reconnectTimes = listOf(13.935.seconds), hrLost = 28.027.seconds, gpsLost = 253.106.seconds, crashes = 0),
            "straight-run-3min" to ReliabilityRecord(drops = 1, reconnects = 1, reconnectTimes = listOf(15.seconds), hrLost = 10.5.seconds, gpsLost = 0.seconds, crashes = 0),
            "slow-walk-5min" to ReliabilityRecord.none,
            "sitting-still-10min" to ReliabilityRecord.none,
        )
        assertEquals(expected, expected.keys.associateWith { ReliabilityRecord.of(play(it)) })
    }
}
