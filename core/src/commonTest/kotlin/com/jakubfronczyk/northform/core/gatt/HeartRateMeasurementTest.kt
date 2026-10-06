package com.jakubfronczyk.northform.core.gatt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Byte fixtures from the GSS "Heart Rate Measurement" layout. These are the spike's day-1 JVM tests. */
class HeartRateMeasurementTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uint8_heart_rate_no_contact_bits() {
        val m = HeartRateMeasurement.parse(bytes(0x00, 72))
        assertEquals(72, m?.bpm)
        assertEquals(false, m?.sensorContactSupported)
        assertEquals(emptyList(), m?.rrIntervalsMs)
        assertNull(m?.energyExpendedKj)
    }

    @Test
    fun uint16_heart_rate_little_endian() {
        // flags 0x01 → 16-bit value; 0x012C = 300
        val m = HeartRateMeasurement.parse(bytes(0x01, 0x2C, 0x01))
        assertEquals(300, m?.bpm)
    }

    @Test
    fun sensor_contact_flags() {
        val m = HeartRateMeasurement.parse(bytes(0x06, 60))
        assertEquals(true, m?.sensorContactSupported)
        assertEquals(true, m?.sensorContactDetected)
    }

    @Test
    fun energy_expended_then_rr_intervals_in_1024ths() {
        // flags: energy (0x08) + RR (0x10); hr 65; energy 0x0064 = 100 kJ; RR 1024 (=1000 ms), 820 (≈801 ms)
        val m = HeartRateMeasurement.parse(bytes(0x18, 65, 0x64, 0x00, 0x00, 0x04, 0x34, 0x03))
        assertEquals(65, m?.bpm)
        assertEquals(100, m?.energyExpendedKj)
        assertEquals(listOf(1000, 801), m?.rrIntervalsMs)
    }

    @Test
    fun rr_present_but_truncated_frame_keeps_whole_pairs_only() {
        val m = HeartRateMeasurement.parse(bytes(0x10, 70, 0x00, 0x04, 0x11))
        assertEquals(listOf(1000), m?.rrIntervalsMs)
    }

    @Test
    fun too_short_frames_return_null_not_throw() {
        assertNull(HeartRateMeasurement.parse(ByteArray(0)))
        assertNull(HeartRateMeasurement.parse(bytes(0x01, 0x2C)))   // uint16 announced, one byte present
        assertNull(HeartRateMeasurement.parse(bytes(0x08, 70, 0x64))) // energy announced, half present
    }

    @Test
    fun polar_zero_means_no_reading_and_is_left_to_the_caller() {
        // The link machine drops bpm == 0 (PolarSensor.swift:175 did the same); the parser reports it faithfully.
        assertEquals(0, HeartRateMeasurement.parse(bytes(0x00, 0))?.bpm)
    }
}
