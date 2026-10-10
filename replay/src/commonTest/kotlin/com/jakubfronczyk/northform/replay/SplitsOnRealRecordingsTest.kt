package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.metrics.Elevation
import com.jakubfronczyk.northform.core.metrics.Pauses
import com.jakubfronczyk.northform.core.metrics.Splits
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.rebuild
import com.jakubfronczyk.northform.testing.RunHarness
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** metrics/04 on the real recordings: the summary's split rows against the live run (D111, D121). */
class SplitsOnRealRecordingsTest {

    private fun fmt(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = round(value * scale).toLong()
        return "${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
    }

    /** Run 1: km 1 took 11:41 (the stairwell start, GPS ✓ only at +307 s), then ~6:40/km; 829.7 m left at the stop. */
    private val PIN_RUN1 = listOf("700.9 700.9 116 +6.6 -9.1", "403.8 403.8 145 +6.5 -4.6", "402.7 402.7 153 +13.1 -13.6", "398.1 398.1 152 +6.5 -11.2", "475.5 573.1 159 +11.5 -7.3")
    /** Run 2: a steady ~13 min/km walk, 987.5 m left at the stop. */
    private val PIN_RUN2 = listOf("849.5 849.5 101 +12.4 -17.1", "740.1 740.1 102 +10.6 -6.6", "865.8 876.7 103 +10.7 -10.4")

    private class Played(val run: RunHarness, val rows: List<Splits.Row>, val start: kotlin.time.Instant)

    private fun play(name: String): Played {
        val script = bundled(name)
        val run = RunHarness.play(script.meta.start, script.runInputs)
        val data = run.stored
        val stoppedAt = data.recording.endUtc!!
        val rows = Splits.table(
            marks = Splits.marks(data.events), startedAt = data.recording.startUtc, stoppedAt = stoppedAt,
            totalDistance = run.state.snapshot.distance, pauses = Pauses.intervals(data.events, stoppedAt), hr = data.hr, fixes = data.fixes,
        )
        return Played(run, rows, script.meta.start)
    }

    /** Done when: the summary's rows end exactly where the live `Split` events were recorded (the D121 moments pinned in `RealRecordingsTest`). */
    @Test
    fun summary_splits_equal_the_live_split_events() {
        val pinned = mapOf(
            "run-1-park" to listOf("1@707.851", "2@1203.725", "3@1667.227", "4@2065.315"),
            "run-2-walk" to listOf("1@856.263", "2@1596.328"),
        )
        for ((name, splits) in pinned) {
            val played = play(name)
            val live = played.run.recordedEvents.filter { it.kind is EventKind.Split }
            val full = played.rows.filter { !it.partial }
            assertEquals(splits, full.map { "${it.index}@${fmt((it.endAt - played.start).inSecondsDouble, 3)}" }, name)
            assertEquals(live.map { it.t }, full.map { it.endAt }, "$name: row ends are the live split moments")
            assertEquals(listOf(played.run.stored.recording.startUtc) + live.dropLast(1).map { it.t }, full.map { it.startAt }, "$name: each row starts where the last ended")
        }
    }

    /** The live reducer's `splitStartActive` is the active time at the last mark: the rows' full-km times must add up to it. */
    @Test
    fun full_split_times_add_up_to_the_live_active_time_at_the_last_mark() {
        for (name in listOf("run-1-park", "run-2-walk")) {
            val played = play(name)
            val summed = played.rows.filter { !it.partial }.fold(Duration.ZERO) { acc, row -> acc + row.time }
            val live = played.run.state.progress.splitStartActive
            assertTrue(abs((summed - live).inSecondsDouble) < 0.000_001, "$name: rows $summed vs live $live")
            val rebuilt = RunReducer.rebuild(RunHarness.config, played.run.stored).progress
            assertEquals(played.rows.filter { !it.partial }.size, rebuilt.splitIndex, "$name: the rebuilt run counts the same marks")
        }
    }

    /** Characterisation pins (no Swift reference): the partial last km and the climb of the two real recordings. */
    @Test
    fun real_recording_rows_stay_put() {
        val run1 = play("run-1-park")
        assertEquals(5, run1.rows.size)
        val last = run1.rows.last()
        assertTrue(last.partial && abs(last.distance - 829.7) < 0.5, "run 1 ends ${last.distance} m into km 5")
        // "time pace avgHr +ascent -descent" per row, pinned 2026-10-10 (characterisation)
        fun line(r: Splits.Row) = "${fmt(r.time.inSecondsDouble, 1)} ${r.pace?.let { fmt(it, 1) } ?: "-"} ${r.avgHr ?: "-"} +${fmt(r.ascent, 1)} -${fmt(r.descent, 1)}"
        assertEquals(PIN_RUN1, run1.rows.map(::line), "run-1-park rows")
        assertEquals(PIN_RUN2, play("run-2-walk").rows.map(::line), "run-2-walk rows")
        val climb = Elevation.compute(run1.run.stored.fixes, Pauses.intervals(run1.run.stored.events, run1.run.stored.recording.endUtc!!))
        assertTrue(climb.ascent > 0 && climb.descent > 0, "a park run has some climb: $climb")
        assertEquals(climb.ascent, run1.rows.sumOf { it.ascent }, "the rows share out the whole climb")
        assertEquals(climb.descent, run1.rows.sumOf { it.descent })
    }
}
