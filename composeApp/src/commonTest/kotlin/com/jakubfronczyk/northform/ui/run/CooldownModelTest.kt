package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** run-flow-artboards: the UI-side trace of `hrNow` since the stop, folded from snapshots. */
class CooldownTraceTest {

    private val stoppedAt = Instant.fromEpochSeconds(1_700_000_000)
    private val cooling = RunSnapshot(RunPhase.Cooldown(stoppedAt + 60.seconds), hrNow = 172)

    private fun CooldownTrace.at(after: Duration, snapshot: RunSnapshot = cooling) = add(snapshot, stoppedAt + after)

    @Test
    fun it_collects_only_in_the_cool_down() {
        val empty = CooldownTrace()
        for (phase in listOf(RunPhase.Gate, RunPhase.Active, RunPhase.Paused(stoppedAt), RunPhase.Countdown(stoppedAt), RunPhase.Done)) {
            assertSame(empty, empty.add(cooling.copy(phase = phase), stoppedAt), "nothing collected in $phase")
        }
        assertEquals(listOf<Int?>(172), empty.at(0.seconds).points)
    }

    @Test
    fun one_point_per_tick_since_the_stop() {
        val trace = CooldownTrace()
            .at(0.seconds).at(300.milliseconds, cooling.copy(hrNow = 171)) // same second: the first reading stands
            .at(1.seconds, cooling.copy(hrNow = 170))
            .at(2.5.seconds, cooling.copy(hrNow = 168))
        assertEquals(listOf<Int?>(172, 170, 168), trace.points)
        assertSame(trace, trace.at(2.9.seconds, cooling.copy(hrNow = 150)), "a tick already drawn is kept")
    }

    @Test
    fun a_lost_sensor_or_a_missed_tick_is_a_gap() {
        val trace = CooldownTrace()
            .at(0.seconds)
            .at(1.seconds, cooling.copy(hrLost = true, hrNow = null))
            .at(4.seconds, cooling.copy(hrNow = 160))
        assertEquals(listOf(172, null, null, null, 160), trace.points)
    }

    @Test
    fun a_trace_begun_late_has_no_start() {
        // The screen was rebuilt 22 s in: the seconds before are unknown, so "Started at" is too.
        val late = CooldownTrace().at(22.seconds, cooling.copy(hrNow = 151))
        assertEquals(23, late.points.size)
        assertNull(late.start)
        assertEquals(151, late.points.last())
    }

    @Test
    fun the_last_tick_is_the_cool_downs_last_second() {
        assertEquals(60, CooldownTrace().at(75.seconds).points.size, "never past the 60 s window")
    }
}

/** run-flow-artboards: the Cool-down artboard (`Cooldown.dc.html`) over the snapshot, the run's clock and the trace. */
class CooldownModelTest {

    private val stoppedAt = Instant.fromEpochSeconds(1_700_000_000)
    private val cooling = RunSnapshot(
        RunPhase.Cooldown(stoppedAt + 60.seconds), totalTime = 52.minutes + 40.seconds, distance = 10_210.0, hrNow = 151,
    )
    /** 172 at the stop, 151 at 0:22 (the artboard's values). */
    private val trace = CooldownTrace(listOf(172) + List(20) { 165 } + listOf(151))

    private fun at(after: Duration, snapshot: RunSnapshot = cooling, t: CooldownTrace = trace) = CooldownModel.from(snapshot, stoppedAt + after, t)

    @Test
    fun the_artboard_measuring() {
        val m = at(22.seconds)
        assertEquals("Run stopped", m.chipLabel)
        assertEquals(Theme.accent, m.chipColor)
        assertEquals("52:40 · 10.21 km", m.totals)
        assertEquals("Cool-down", m.title)
        assertEquals("Stand or walk slowly. Measuring how fast your heart calms down.", m.subtitle)
        assertEquals("0:38", m.left)
        assertEquals("151", m.hrNow)
        assertEquals("172", m.startedAt)
        assertEquals("−21", m.drop)
        assertFalse(m.lost)
        assertEquals("Heart rate since stop · 0:22 of 1:00", m.caption)
        assertNull(m.lostLine)
    }

    @Test
    fun the_time_left_rounds_up_and_the_ring_fills_with_the_time_gone() {
        // (time since the stop, left, ring): the artboard's dashoffset = c·remaining/60 lights the time gone.
        val table = listOf(
            Triple(0.seconds, "1:00", 0f), Triple(0.4.seconds, "1:00", 0f), Triple(1.seconds, "0:59", 1 / 60f),
            Triple(22.seconds, "0:38", 22 / 60f), Triple(59.5.seconds, "0:01", 59 / 60f), Triple(61.seconds, "0:00", 1f),
        )
        for ((after, left, ring) in table) {
            val m = at(after)
            assertEquals(left, m.left, "left at $after")
            assertEquals(ring, m.ring, 0.0001f, "ring at $after")
        }
    }

    @Test
    fun the_sensor_lost_variant_says_so_and_shows_no_numbers_it_cannot_trust() {
        val m = at(22.seconds, cooling.copy(hrLost = true, hrNow = null))
        assertTrue(m.lost)
        assertEquals(NO_VALUE, m.hrNow)
        assertEquals(NO_VALUE, m.drop)
        assertEquals("172", m.startedAt, "where it started is still known (the artboard keeps it)")
        assertEquals("Sensor lost · recovery can't be measured", m.lostLine)
        assertNull(m.caption)
    }

    @Test
    fun an_empty_or_late_trace_has_no_start_and_no_drop() {
        val empty = at(22.seconds, t = CooldownTrace())
        assertEquals(NO_VALUE, empty.startedAt)
        assertEquals(NO_VALUE, empty.drop)
        assertEquals("151", empty.hrNow, "now still comes from the snapshot")
        val late = at(22.seconds, t = CooldownTrace(List(22) { null } + listOf(151)))
        assertEquals(NO_VALUE, late.startedAt)
    }

    @Test
    fun a_heart_rate_that_rose_shows_a_plus() {
        assertEquals("0", at(22.seconds, cooling.copy(hrNow = 172)).drop)
        assertEquals("+3", at(22.seconds, cooling.copy(hrNow = 175)).drop)
    }

    @Test
    fun a_walk_says_walk_stopped_in_its_colour() {
        val m = at(22.seconds, cooling.copy(type = RecordingType.Walk))
        assertEquals("Walk stopped", m.chipLabel)
        assertEquals(Theme.text2, m.chipColor)
    }

    @Test
    fun the_chart_places_each_point_on_the_minute_and_the_drop() {
        // x: the tick over 60 s; y: 0 at the start's level, 1 at CHART_SPAN_BPM below it; a gap stays a gap.
        val m = at(4.seconds, t = CooldownTrace(listOf(172, null, 162, 152)))
        assertEquals(4, m.chart.size)
        assertEquals(CooldownModel.ChartPoint(0f, 0f), m.chart[0])
        assertNull(m.chart[1])
        assertEquals(CooldownModel.ChartPoint(2 / 60f, 10f / CooldownModel.CHART_SPAN_BPM), m.chart[2])
        assertEquals(CooldownModel.ChartPoint(3 / 60f, 20f / CooldownModel.CHART_SPAN_BPM), m.chart[3])
        assertEquals(1f, at(4.seconds, t = CooldownTrace(listOf(172, 100))).chart[1]!!.y, "a drop past the span sits on the floor")
        assertEquals(0f, at(4.seconds, t = CooldownTrace(listOf(172, 180))).chart[1]!!.y, "a rise sits on the start's line")
        assertEquals(emptyList(), at(4.seconds, t = CooldownTrace(listOf(null, 160))).chart.filterNotNull(), "no start, no scale")
    }
}
