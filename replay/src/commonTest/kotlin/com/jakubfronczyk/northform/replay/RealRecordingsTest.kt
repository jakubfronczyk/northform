package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.metrics.Geo
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.wire
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.rebuild
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import com.jakubfronczyk.northform.testing.t0
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * THE GATE (walking-skeleton step 4): the bundled recordings replayed through the Kotlin reducer must
 * match the Swift pins exactly. Ports `GPSDistanceTests`, `StraightRunTests`, `DriftTests`,
 * `PaceCharacterizationTests`, `CurrentPaceTests` and `HRLostAlertTests` where they read fixtures.
 */
class RealRecordingsTest {

    private fun play(script: ReplayScript, failing: Set<Int> = emptySet(), tickOffset: Duration = Duration.ZERO, confirmAfter: Int = 0) =
        RunHarness.play(script.meta.start, script.runInputs, failing, tickOffset, confirmAfter)

    private fun timeline(script: ReplayScript, tickOffset: Duration = Duration.ZERO) =
        RunHarness.timeline(script.meta.start, script.runInputs, tickOffset = tickOffset)

    private class Expected(val distanceMeters: Double, val toleranceMeters: Double, val totalTimeSeconds: Double?)

    private fun expected(name: String): Expected {
        val o = Json.parseToJsonElement(bundledExpected(name)).jsonObject
        return Expected(
            o["distanceMeters"]!!.jsonPrimitive.double,
            o["toleranceMeters"]!!.jsonPrimitive.double,
            o["totalTimeSeconds"]?.jsonPrimitive?.double,
        )
    }

    /** `String(format: "%.<d>f")` for a non-negative value. */
    private fun fmt(value: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = round(value * scale).toLong()
        return "${scaled / scale}.${(scaled % scale).toString().padStart(decimals, '0')}"
    }

    // ── GPSDistanceTests.swift:93 — the pinned totals (D107 as built) ──────────────────────────

    @Test
    fun real_recording_totals_stay_put() {
        for ((name, metres) in listOf("run-1-park" to 4829.7, "run-2-walk" to 2987.5, "straight-run-3min" to 477.0)) {
            val run = play(bundled(name))
            assertTrue(abs(run.state.snapshot.distance - metres) < 0.5, "$name: ${run.state.snapshot.distance} m, pinned $metres")
        }
    }

    // ── DriftTests.swift ──────────────────────────────────────────────────────────────────────

    @Test
    fun sitting_still_counts_no_distance_and_shows_no_pace() {
        val script = bundled("sitting-still-10min")
        val run = RunHarness(RunHarness.config)
        for (input in timeline(script)) {
            run.send(input)
            assertEquals(null, run.state.snapshot.splitPace, "no split pace while sitting")
            assertEquals(null, run.state.snapshot.currentPace, "no current pace while sitting")
        }
        assertEquals(expected("sitting-still-10min").distanceMeters, run.state.snapshot.distance)
    }

    @Test
    fun slow_walk_adds_up_despite_steps_smaller_than_the_accuracy() {
        val truth = expected("slow-walk-5min")
        val run = play(bundled("slow-walk-5min"))
        assertTrue(abs(run.state.snapshot.distance - truth.distanceMeters) <= truth.toleranceMeters, "${run.state.snapshot.distance} m vs ${truth.distanceMeters} m")
    }

    // ── StraightRunTests.swift ────────────────────────────────────────────────────────────────

    @Test
    fun straight_run_matches_expected_distance_and_total_time() {
        val script = bundled("straight-run-3min")
        val truth = expected("straight-run-3min")
        val run = play(script)
        val snapshot = run.state.snapshot
        assertTrue(abs(snapshot.distance - truth.distanceMeters) <= truth.toleranceMeters, "${snapshot.distance} m")
        assertEquals(truth.totalTimeSeconds, snapshot.totalTime.inSecondsDouble)
        assertEquals(script.meta.start + 5.seconds, snapshot.startedAt)
        assertEquals(RunPhase.Done, snapshot.phase)
        assertEquals(RecordingState.Computing, run.stored.recording.state)
        assertEquals(script.meta.start + 185.25.seconds, run.stored.recording.endUtc)
    }

    @Test
    fun route_has_a_break_at_the_pause_and_matches_the_counted_distance() {
        val script = bundled("straight-run-3min")
        val truth = expected("straight-run-3min")
        val run = play(script)
        assertEquals(2, run.route.segments.size, "before and after the pause")
        val drawn = run.route.segments.sumOf { segment ->
            segment.zipWithNext { a, b ->
                Geo.distance(LocationFix(Instant.DISTANT_PAST, a.latitude, a.longitude, 0.0), LocationFix(Instant.DISTANT_PAST, b.latitude, b.longitude, 0.0))
            }.sum()
        }
        assertTrue(abs(drawn - truth.distanceMeters) <= truth.toleranceMeters, "the map shows what was counted")
    }

    @Test
    fun straight_run_records_the_right_events() {
        val script = bundled("straight-run-3min")
        val run = play(script)
        val kinds = run.stored.events.map { "${it.kind.wire}@${fmt((it.t - script.meta.start).inSecondsDouble, 2)}" }
        assertEquals(
            listOf(
                "sensorLost@60.20",
                "hrLost@65.00", // last sample 59.5 s, lost from the first tick ≥ 64.5 s
                "sensorReconnected@75.20",
                "hrBack@75.50",
                "pause@125.25",
                "resume@145.25",
                "stop@185.25",
                "cooldownEnd@245.25",
            ),
            kinds,
        )
        assertEquals(List(5) { Alert.CountdownTick } + Alert.HrLost, run.alerts)
    }

    @Test
    fun every_sample_after_start_is_stored_exactly_once() {
        val script = bundled("straight-run-3min")
        val startedAt = script.meta.start + 5.seconds
        val expectedHr = script.inputs.filterIsInstance<ReplayInput.Hr>().map { it.sample }.filter { it.t >= startedAt }
        val expectedFixes = script.inputs.filterIsInstance<ReplayInput.Fix>().map { it.fix }.filter { it.t >= startedAt && it.t <= startedAt + 180.25.seconds }
        val run = play(script)
        assertEquals(expectedHr, run.stored.hr)
        assertEquals(expectedFixes, run.stored.fixes)
    }

    @Test
    fun nothing_is_lost_while_a_batch_is_in_flight() {
        val script = bundled("straight-run-3min")
        val clean = play(script)
        val slow = play(script, confirmAfter = 3)
        assertEquals(clean.stored.hr, slow.stored.hr)
        assertEquals(clean.stored.fixes, slow.stored.fixes)
        assertEquals(clean.stored.events, slow.stored.events)
        assertEquals(RecordingState.Computing, slow.stored.recording.state)
    }

    @Test
    fun failed_write_is_retried_without_loss_or_duplicates() {
        val script = bundled("straight-run-3min")
        val clean = play(script)
        for (failingSeq in listOf(1, 3, 20)) {
            val withFailure = play(script, failing = setOf(failingSeq))
            assertEquals(clean.stored.hr, withFailure.stored.hr, "seq $failingSeq")
            assertEquals(clean.stored.fixes, withFailure.stored.fixes, "seq $failingSeq")
            assertEquals(clean.stored.events, withFailure.stored.events, "seq $failingSeq")
            assertEquals(RecordingState.Computing, withFailure.stored.recording.state)
        }
    }

    /** D94: everything in RunProgress can be rebuilt from the store, compared at flushes. */
    private fun checkRebuild(script: ReplayScript, name: String, tickOffset: Duration, confirmAfter: Int = 0, every: Int = 1, minimumFlushes: Int = 36) {
        val run = play(script, tickOffset = tickOffset, confirmAfter = confirmAfter)
        assertTrue(run.checkpoints.size >= minimumFlushes, "$name: ${run.checkpoints.size} flushes")
        val last = run.checkpoints.size - 1
        for ((index, checkpoint) in run.checkpoints.withIndex()) {
            if (index % every != 0 && index != last) continue
            val data = checkpoint.data
            val heartbeat = assertNotNull(data.lastPersistedAt)
            val live = RunReducer.reduce(checkpoint.state, RunInput.Tick(heartbeat)).state
            val rebuilt = RunReducer.rebuild(RunHarness.config, data)
            assertEquals(
                live.progress, rebuilt.progress,
                "$name, ticks +$tickOffset, confirm after $confirmAfter: at ${(heartbeat - script.meta.start).inSecondsDouble} s",
            )
        }
    }

    @Test
    fun rebuild_from_store_matches_live_progress_at_every_flush() {
        val script = bundled("straight-run-3min")
        for (confirmAfter in listOf(0, 1, 3)) {
            for (offset in listOf(0.0, 0.3, 0.7)) checkRebuild(script, "straight-run-3min", offset.seconds, confirmAfter)
            val run = play(script, confirmAfter = confirmAfter)
            assertTrue(run.checkpoints.any { it.data.events.lastOrNull()?.kind == EventKind.Pause }, "covers a pause flush")
        }
    }

    @Test
    fun rebuild_matches_live_on_real_runs() {
        for (name in listOf("run-1-park", "run-2-walk")) {
            val script = bundled(name)
            for (confirmAfter in listOf(0, 3)) for (offset in listOf(0.0, 0.3, 0.7)) {
                checkRebuild(script, name, offset.seconds, confirmAfter, every = 20)
            }
        }
    }

    /** ADR 0006: a batch's heartbeat is never older than the data saved with it. */
    @Test
    fun heartbeat_is_never_older_than_the_data_it_covers() {
        val script = bundled("straight-run-3min")
        for (confirmAfter in listOf(0, 1, 3)) for (offset in listOf(0.0, 0.3, 0.7)) {
            val run = play(script, tickOffset = offset.seconds, confirmAfter = confirmAfter)
            for (checkpoint in run.checkpoints) {
                val store = checkpoint.data
                val newest = (store.hr.map { it.t } + store.fixes.map { it.t } + store.events.filter { it.kind != EventKind.Stop }.map { it.t }).maxOrNull()
                val heartbeat = store.lastPersistedAt
                if (newest != null && heartbeat != null) assertTrue(heartbeat >= newest, "ticks +$offset s, confirm after $confirmAfter")
            }
        }
    }

    @Test
    fun rebuild_counts_a_fix_stamped_at_the_pause_moment() {
        val inputs = ArrayList<ReplayInput>()
        inputs += ReplayInput.User(UserAction.Start, at(0))
        inputs += ReplayInput.Fix(fix(4, north = 0.0))
        for (s in 6..25) inputs += ReplayInput.Fix(fix(s, north = 3.0 * (s - 5))) // 60 m, the last fix at 25 s
        inputs += ReplayInput.User(UserAction.Pause, at(25)) // the same instant
        val script = ReplayScript(ReplayMeta(RecordingType.Run, t0, 187, 60, Sex.Male), inputs)

        val run = play(script)
        val rebuilt = RunReducer.rebuild(RunHarness.config, run.stored)
        assertTrue(abs(run.state.snapshot.distance - 60) < 0.5, "live counts the fix at the pause moment")
        assertTrue(abs(rebuilt.progress.distance - 60) < 0.5, "${rebuilt.progress.distance} m: the rebuild counts it too")
    }

    /** D113: fixes to 60.0 s, the next at 70.5 s, live ticks at x.7 — a GPS gap is judged from the fixes alone. */
    @Test
    fun rebuild_matches_live_across_a_gap_near_ten_seconds() {
        val inputs = ArrayList<ReplayInput>()
        inputs += ReplayInput.User(UserAction.Start, at(0))
        inputs += ReplayInput.Fix(fix(4, north = 0.0))
        for (s in 6..60) inputs += ReplayInput.Fix(fix(s, north = 3.0 * (s - 5)))
        var s = 70.5
        while (s <= 120.5) { inputs += ReplayInput.Fix(fix(s, north = 3 * (s - 5))); s += 1 }
        inputs += ReplayInput.User(UserAction.Stop, at(125))
        inputs += ReplayInput.User(UserAction.SkipCooldown, at(126))
        val script = ReplayScript(ReplayMeta(RecordingType.Run, t0, 187, 60, Sex.Male), inputs)
        checkRebuild(script, "gap near 10 s", 0.7.seconds, minimumFlushes = 21)
    }

    // ── PaceCharacterizationTests.swift — today's pace and splits on the real recordings, pinned ──

    private class Trace(val paces: List<String>, val splits: List<String>)

    private fun paceTrace(name: String): Trace {
        val script = bundled(name)
        val run = RunHarness(RunHarness.config)
        val paces = ArrayList<String>()
        fun format(value: Double?) = value?.let { fmt(it, 3) } ?: "-"
        for (input in timeline(script)) {
            run.send(input)
            if (input is RunInput.Tick) paces += "${format(run.state.snapshot.currentPace)} ${format(run.state.snapshot.splitPace)}"
        }
        run.drain()
        val splits = run.recordedEvents.mapNotNull { event ->
            (event.kind as? EventKind.Split)?.let { "${it.index}@${fmt((event.t - script.meta.start).inSecondsDouble, 3)}" }
        }
        return Trace(paces, splits)
    }

    /** FNV-1a, 64 bit: stable across runs and platforms. */
    private fun fnv1a(lines: List<String>): ULong {
        var hash = 0xcbf29ce484222325uL
        for (byte in lines.joinToString("\n").encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3uL
        }
        return hash
    }

    private class Pinned(val ticks: Int, val swiftHash: ULong, val spots: Map<Int, String>, val splits: List<String>)

    /** Pinned in Swift 2026-10-05 (aa1e554, then D121). The hash is of the whole "%.3f" series. */
    private val pinned = mapOf(
        "run-1-park" to Pinned(
            ticks = 2613, swiftHash = 0xda5eb2d789651319uL,
            spots = mapOf(698 to "444.597 709.120", 1382 to "390.530 386.284", 2025 to "381.991 396.463"),
            splits = listOf("1@707.851", "2@1203.725", "3@1667.227", "4@2065.315"),
        ),
        "run-2-walk" to Pinned(
            ticks = 2530, swiftHash = 0xd8c832306289c40auL,
            spots = mapOf(613 to "699.157 932.778", 1276 to "751.842 784.951", 1901 to "609.895 703.739"),
            splits = listOf("1@856.263", "2@1596.328"),
        ),
    )

    @Test
    fun pace_and_splits_on_real_recordings_stay_exactly() {
        for ((name, expected) in pinned) {
            val trace = paceTrace(name)
            assertEquals(expected.ticks, trace.paces.size, "$name ticks")
            for ((tick, values) in expected.spots) assertEquals(values, trace.paces[tick], "$name tick $tick")
            assertEquals(expected.splits, trace.splits, "$name splits")
            assertEquals(expected.swiftHash.toString(16), fnv1a(trace.paces).toString(16), "$name: the pace series differs from the Swift series somewhere")
        }
    }

    // ── CurrentPaceTests.swift (run-1) + GPSDistanceTests.swift (run-2 p95) ────────────────────

    private class Series(val pace: List<Double?>, val filling: List<Boolean>)

    private fun paceSeries(script: ReplayScript): Series {
        val run = RunHarness(RunHarness.config)
        val pace = ArrayList<Double?>()
        val filling = ArrayList<Boolean>()
        var activeSince: Instant? = null
        for (input in timeline(script)) {
            run.send(input)
            if (input !is RunInput.Tick) continue
            if (run.state.snapshot.phase != RunPhase.Active) { activeSince = null; continue }
            val since = activeSince ?: input.t.also { activeSince = it }
            pace += run.state.snapshot.currentPace
            filling += input.t - since < 60.seconds
        }
        return Series(pace, filling)
    }

    /** Tick-to-tick pace changes outside the first 60 s after a start or resume, sorted. */
    private fun paceChanges(series: Series): List<Double> {
        val changes = ArrayList<Double>()
        for (i in 1 until series.pace.size) {
            if (series.filling[i] || series.filling[i - 1]) continue
            val a = series.pace[i - 1] ?: continue
            val b = series.pace[i] ?: continue
            changes += abs(b - a)
        }
        return changes.sorted()
    }

    private fun List<Double>.p95() = this[((size - 1) * 0.95).toInt()]

    /** Run 1 (42 min in a park, real GPS): measured p95 7.1, max 36.8 s/km on D107 distance. */
    @Test
    fun run_one_current_pace_is_steady() {
        val changes = paceChanges(paceSeries(bundled("run-1-park")))
        assertTrue(changes.size > 1500, "covers the run")
        assertTrue(changes.p95() <= 8, "p95 ${changes.p95()} s/km per tick")
        assertTrue(changes.last() <= 45, "max ${changes.last()} s/km per tick")
    }

    /** Run 2 (41 min steady walk): measured p95 25.8 s/km with D107, 34.7 with D102. */
    @Test
    fun run_two_current_pace_is_steady_at_a_constant_walk() {
        val changes = paceChanges(paceSeries(bundled("run-2-walk")))
        assertTrue(changes.p95() <= 28, "p95 ${changes.p95()} s/km per tick")
    }

    @Test
    fun run_one_current_pace_is_the_same_on_every_replay() {
        val script = bundled("run-1-park")
        assertEquals(paceSeries(script).pace, paceSeries(script).pace)
    }

    // ── HRLostAlertTests.swift:34 — run 2 ─────────────────────────────────────────────────────

    /** The sensor went quiet 5.6 s then delivered a burst; a tick landed in between (ticks at .93). One real loss buzzes. */
    @Test
    fun run_two_buzzes_for_the_real_loss_only() {
        val run = RunHarness(RunHarness.config)
        for (input in timeline(bundled("run-2-walk"), tickOffset = 0.93.seconds)) run.send(input)
        assertEquals(2, run.stored.events.count { it.kind == EventKind.HrLost }, "both gaps are still recorded")
        assertEquals(1, run.alerts.count { it == Alert.HrLost })
    }

}
