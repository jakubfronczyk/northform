package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.metrics.zone
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The LiveDark artboard's rules as a table over [LiveRunModel.from] (H1, D106, D22). */
class LiveRunModelTest {

    private val t0 = Instant.fromEpochSeconds(1_700_000_000)
    private val running = RunSnapshot(
        phase = RunPhase.Active, startedAt = t0, totalTime = 2058.seconds, distance = 6420.0,
        splitPace = 312.0, currentPace = 304.0, hrNow = 152, hrAvg = 148, hrMax = 171, zone = HRZones.standard.zone(3),
    )

    private fun model(snapshot: RunSnapshot, now: Instant = t0 + 2058.seconds) = LiveRunModel.from(snapshot, now)

    @Test
    fun running_shows_every_number_with_avg_and_max() {
        val m = model(running)
        assertEquals(LiveRunModel.Hr.Reading("152", HRZones.standard.zone(3)), m.hr)
        assertEquals("148", m.hrAvg)
        assertEquals("171", m.hrMax)
        assertEquals("5:05", m.pace, "current pace 304 rounds to 5:05 (D22)")
        assertEquals("5:12", m.split)
        assertEquals("6.42", m.distance)
        assertEquals("34:18", m.time)
        assertEquals(3, m.litZone)
        assertEquals(false, m.paused)
        assertNull(m.pausedFor)
    }

    @Test
    fun hr_lost_mid_run_keeps_the_averages_and_lights_no_zone() {
        val m = model(running.copy(hrLost = true, hrNow = null, zone = null))
        assertEquals(LiveRunModel.Hr.Lost, m.hr)
        assertEquals("148", m.hrAvg)
        assertEquals("171", m.hrMax)
        assertNull(m.litZone)
    }

    @Test
    fun no_sample_yet_shows_dashes_for_now_avg_and_max() {
        val m = model(running.copy(hrLost = true, hrNow = null, hrAvg = null, hrMax = null, zone = null))
        assertEquals(LiveRunModel.Hr.Lost, m.hr)
        assertEquals(NO_VALUE, m.hrAvg)
        assertEquals(NO_VALUE, m.hrMax)
    }

    @Test
    fun hr_reading_without_a_number_is_a_dash_not_a_stale_value() {
        val m = model(running.copy(hrNow = null, zone = null))
        assertEquals(LiveRunModel.Hr.Reading(NO_VALUE, null), m.hr)
    }

    @Test
    fun paused_counts_the_pause_on_the_run_clock_and_dims_the_values() {
        val since = t0 + 2000.seconds
        val m = model(running.copy(phase = RunPhase.Paused(since), currentPace = null), now = since + 42.seconds)
        assertEquals(true, m.paused)
        assertEquals(42.seconds, m.pausedFor)
        assertEquals(NO_VALUE, m.pace)
        assertEquals(true, m.paceDimmed)
        assertEquals(true, m.valuesDimmed)
    }

    @Test
    fun gps_lost_hides_both_paces_and_puts_distance_on_hold() {
        val m = model(running.copy(gpsLost = true))
        assertEquals(NO_VALUE, m.pace)
        assertEquals(NO_VALUE, m.split)
        assertEquals("on hold", m.distanceSub)
        assertEquals(true, m.gpsLost)
        assertEquals("6.42", m.distance, "the counted distance stays")
    }

    @Test
    fun no_current_pace_under_20_m_is_a_dash_while_the_split_stays() {
        val m = model(running.copy(currentPace = null))
        assertEquals(NO_VALUE, m.pace)
        assertEquals("5:12", m.split)
        assertEquals(false, m.paceDimmed)
    }
}
