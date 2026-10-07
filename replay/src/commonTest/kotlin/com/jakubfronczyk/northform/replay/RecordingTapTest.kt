package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** `Tests/ReplayTests/RecordingTapTests.swift` in spirit: sorted output, the 30 s rewrite cadence, inputs before start dropped. */
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
}
