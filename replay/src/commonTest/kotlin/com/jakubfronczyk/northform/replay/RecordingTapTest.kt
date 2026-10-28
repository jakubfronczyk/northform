package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** `Tests/ReplayTests/RecordingTapTests.swift` in spirit: sorted output, the 30 s rewrite cadence, inputs before start dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingTapTest {
    private val start = Fixture.defaultStart
    private val meta = ReplayMeta(RecordingType.Run, start, 187, 60, Sex.Male)

    @Test
    fun writes_a_parseable_fixture_sorted_by_time_dropping_inputs_before_start() {
        val written = ArrayList<String>()
        val tap = RecordingTap(meta) { written += it }
        tap.record(ReplayInput.Hr(HrSample(start - 1.seconds, 70))) // before start: dropped
        tap.record(ReplayInput.Hr(HrSample(start + 2.seconds, 120)))
        tap.record(ReplayInput.Fix(LocationFix(start + 1.seconds, 52.5, 13.4, 5.0))) // older than the last HR sample
        tap.write()

        val script = Fixture.parse(written.single())
        assertEquals(meta, script.meta)
        assertEquals(listOf(start + 1.seconds, start + 2.seconds), script.inputs.map { it.t })
    }

    @Test
    fun ticks_rewrite_the_file_every_30_seconds_of_run_time() {
        var writes = 0
        val tap = RecordingTap(meta) { writes++ }
        tap.tick(start) // first tick only arms the clock
        tap.tick(start + 29.seconds)
        assertEquals(0, writes)
        tap.tick(start + 30.seconds)
        assertEquals(1, writes)
        tap.tick(start + 45.seconds)
        assertEquals(1, writes)
        tap.tick(start + 60.seconds)
        assertEquals(2, writes)
    }

    // ── replay/01: fixture → run → tap → fixture, byte for byte ─────────────────────────────

    /** The reducer's input stream (what `RecordingSession` hands its observer) back through the tap gives the file it came from. */
    @Test
    fun observing_a_fixtures_run_inputs_writes_the_same_bytes() {
        for (name in listOf("run-1-park", "run-2-walk", "sitting-still-10min", "slow-walk-5min", "straight-run-3min")) {
            val text = resourceText("fixtures/$name.jsonl")
            val script = Fixture.parse(text)
            val written = ArrayList<String>()
            val tap = RecordingTap(script.meta) { written += it }
            for (input in RunHarness.timeline(script.meta.start, script.runInputs)) tap.observe(input) // ticks interleaved, as live
            tap.write()
            if (swiftByteParity) assertEquals(text, written.last(), name) else assertEquals(Fixture.parse(text), Fixture.parse(written.last()), name)
        }
    }

    /** Inputs a fixture does not store are not recorded; ticks only drive the rewrite cadence. */
    @Test
    fun observe_ignores_inputs_the_format_does_not_store_and_ticks_on_ticks() {
        val written = ArrayList<String>()
        val tap = RecordingTap(meta) { written += it }
        tap.observe(RunInput.Backgrounded(start))
        tap.observe(RunInput.Persisted(1))
        tap.observe(RunInput.PersistFailed(2))
        tap.observe(RunInput.Tick(start))
        tap.observe(RunInput.Tick(start + 30.seconds))
        assertEquals(1, written.size, "the second tick, 30 s on, rewrote the file")
        assertEquals(emptyList(), Fixture.parse(written.single()).inputs)
    }

    /**
     * Through the real session on a virtual clock: replay the fixture, let `RecordingSession` hand every
     * input to the tap, and the tap writes the fixture back. The session publishes the sensor's initial
     * state as a `Sensor` input at t = 0, so the script spells that line out (the format's own rule,
     * `ReplayTimeline.initialSensorState`); the bundled corpus leaves it implicit and would differ there.
     */
    @Test
    fun a_fixture_replayed_through_the_session_is_written_back_byte_identical() = runTest {
        val source = bundled("straight-run-3min")
        val script = ReplayScript(source.meta, listOf(ReplayInput.Sensor(SensorState.Connected(battery = null), source.meta.start)) + source.inputs)
        val text = Fixture.encode(script)

        val wallClock = virtualWallClock(script.meta.start)
        val timeline = ReplayTimeline(script, wallClock)
        val written = ArrayList<String>()
        val tap = RecordingTap(script.meta) { written += it }
        val session = RecordingSession(RunHarness.config, ReplaySensor(timeline), ReplayLocation(timeline), InMemoryRecordingStore(), RecordedAlerts(), wallClock, tap::observe)

        val running = launch { session.run() }
        val taps = launch { timeline.userActions().collect { session.send(it.action, it.t) } }
        runCurrent()
        val playing = launch { timeline.play() }
        advanceTimeBy(260.seconds); runCurrent()
        running.join() // returns after the run's last write; a hang fails the test (runTest timeout)
        taps.cancel(); playing.cancel()
        tap.write()

        assertEquals(text, written.last())
    }
}
