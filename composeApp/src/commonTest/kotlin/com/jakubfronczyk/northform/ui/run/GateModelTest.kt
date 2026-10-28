package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Gate artboard's states over the engine/01 readiness fields (R24, E1, E13), option A: Start is
 * enabled only with HR ✓ and GPS ✓, otherwise the label says what is missing and "Start anyway" shows.
 */
class GateModelTest {

    /** The initial snapshot: nothing seen yet (`StartGateTest.indoors_nothing_is_ready`). */
    private val nothingYet = RunSnapshot(RunPhase.Gate, hrLost = true, gpsLost = true)
    private val hrOnly = nothingYet.copy(hrLost = false, hrNow = 74)
    private val gpsOnly = nothingYet.copy(gpsLost = false)
    private val ready = nothingYet.copy(hrLost = false, hrNow = 74, gpsLost = false)

    @Test
    fun waiting_for_gps_with_heart_rate_ready_is_the_artboard_default() {
        val m = GateModel.from(hrOnly)
        assertEquals("Getting ready", m.title)
        assertEquals("Waiting for GPS. Heart rate is ready.", m.subtitle)
        assertEquals(GateModel.HrRow.Ready("74"), m.hr)
        assertFalse(m.gpsReady)
        assertEquals("GPS · finding signal…", m.gpsTitle)
        assertEquals("0 of 3 fixes", m.fixLabel)
        assertEquals("Start · waiting for GPS", m.startLabel)
        assertFalse(m.startEnabled)
        assertTrue(m.showStartAnyway)
        assertNull(m.battery, "no sensor info: the battery row is hidden")
        assertNull(m.warning)
    }

    @Test
    fun gps_ready_but_no_heart_rate_waits_for_the_sensor() {
        val m = GateModel.from(gpsOnly)
        assertEquals(GateModel.HrRow.Waiting, m.hr)
        assertEquals("Waiting for heart rate. GPS is ready.", m.subtitle)
        assertEquals("3 of 3 fixes", m.fixLabel)
        assertEquals("Start · waiting for heart rate", m.startLabel)
        assertFalse(m.startEnabled)
    }

    @Test
    fun nothing_seen_yet_waits_for_both() {
        val m = GateModel.from(nothingYet)
        assertEquals("Waiting for heart rate and GPS.", m.subtitle)
        assertFalse(m.startEnabled)
        assertTrue(m.showStartAnyway)
    }

    @Test
    fun ready_enables_start_and_hides_start_anyway() {
        val m = GateModel.from(ready, GateSensor(connected = true, batteryPercent = 82, paired = true))
        assertEquals("Ready to run", m.title)
        assertEquals("Heart rate and GPS are locked in", m.subtitle)
        assertEquals("GPS", m.gpsTitle)
        assertEquals("Start run", m.startLabel)
        assertTrue(m.startEnabled)
        assertFalse(m.showStartAnyway)
        assertEquals(GateModel.Battery(82, low = false), m.battery)
        assertNull(m.warning)
    }

    @Test
    fun no_sensor_offers_a_gps_only_run_through_start_anyway() {
        val m = GateModel.from(ready.copy(hrLost = true, hrNow = null), GateSensor(connected = false, batteryPercent = null, paired = true))
        assertEquals("No heart rate yet", m.title)
        assertEquals("Connect the sensor, or start a GPS only run.", m.subtitle)
        assertEquals(GateModel.HrRow.NoSensor(GateModel.SensorAction.Connect), m.hr)
        assertEquals("Start · sensor not connected", m.startLabel)
        assertFalse(m.startEnabled)
        assertTrue(m.showStartAnyway)
        assertNull(m.battery)
    }

    @Test
    fun nothing_paired_offers_pairing_not_a_connect_that_has_nothing_to_connect() {
        val m = GateModel.from(ready.copy(hrLost = true, hrNow = null), GateSensor(connected = false, batteryPercent = null, paired = false))
        assertEquals(GateModel.HrRow.NoSensor(GateModel.SensorAction.Pair), m.hr)
        assertEquals("Pair a sensor", GateModel.SensorAction.Pair.label)
        assertEquals("Pair a sensor, or start a GPS only run.", m.subtitle)
        assertTrue(m.showStartAnyway, "a GPS only run is still one tap away")
    }

    @Test
    fun low_battery_warns_below_20_percent_but_does_not_hold_start() {
        val m = GateModel.from(ready, GateSensor(connected = true, batteryPercent = 14, paired = true))
        assertEquals(GateModel.Battery(14, low = true), m.battery)
        assertEquals("Sensor battery 14 % · may not last the run", m.warning)
        assertEquals("GPS is locked in. Sensor battery is low.", m.subtitle)
        assertTrue(m.startEnabled, "E1 warns, it never blocks")
        assertNull(GateModel.from(ready, GateSensor(true, 20, paired = true)).warning, "20 % is not low")
    }

    @Test
    fun a_heart_rate_reading_without_a_number_is_a_dash() {
        assertEquals(GateModel.HrRow.Ready(NO_VALUE), GateModel.from(ready.copy(hrNow = null)).hr)
    }

    /** today-screen/04: a walk's gate says walk wherever the run's says run, chip included. */
    @Test
    fun a_walk_gate_uses_its_own_noun() {
        val walk = ready.copy(type = RecordingType.Walk)
        val m = GateModel.from(walk)
        assertEquals("Walk", m.typeLabel)
        assertEquals("Ready to walk", m.title)
        assertEquals("Start walk", m.startLabel)
        assertEquals("Connect the sensor, or start a GPS only walk.", GateModel.from(walk, GateSensor(connected = false, batteryPercent = null, paired = true)).subtitle)
        assertEquals("Pair a sensor, or start a GPS only walk.", GateModel.from(walk, GateSensor(connected = false, batteryPercent = null, paired = false)).subtitle)
        assertEquals("Sensor battery 14 % · may not last the walk", GateModel.from(walk, GateSensor(connected = true, batteryPercent = 14, paired = true)).warning)
        assertEquals("Run", GateModel.from(ready).typeLabel)
    }
}
