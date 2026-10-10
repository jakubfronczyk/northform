package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StreamCodecTest {
    private val t0 = Instant.parse("2026-10-10T06:00:00.123456Z")

    @Test
    fun hr_is_ten_bytes_a_sample_and_round_trips() {
        val samples = listOf(HrSample(t0, 0), HrSample(t0 + 1.seconds + 1.nanoseconds, 150), HrSample(t0 + 3600.seconds, 65535))
        val bytes = StreamCodec.encodeHr(samples, t0)
        assertEquals(30, bytes.size)
        assertEquals(samples, StreamCodec.decodeHr(bytes, t0, samples.size))
        assertFailsWith<IllegalArgumentException> { StreamCodec.encodeHr(listOf(HrSample(t0, 70000)), t0) }
    }

    @Test
    fun gps_keeps_every_double_bit_for_bit_and_every_null_null() {
        val fixes = listOf(
            LocationFix(t0, 1.0 / 3, -2.0 / 3, 5.0),
            LocationFix(t0 + 2.seconds, 0.1 + 0.2, Double.MIN_VALUE, Double.MAX_VALUE, altitude = -0.0, speed = Double.NaN, stationary = false),
            LocationFix(t0 + 4.seconds, 0.0, 0.0, 0.0, stationary = true, speedAccuracy = 1.0, course = 359.9, courseAccuracy = 0.0, verticalAccuracy = 9.0),
        )
        val decoded = StreamCodec.decodeGps(StreamCodec.encodeGps(fixes, t0), t0, fixes.size)
        assertEquals(fixes.size, decoded.size)
        for ((a, b) in fixes.zip(decoded)) {
            assertEquals(a.t, b.t)
            assertEquals(a.latitude.toRawBits(), b.latitude.toRawBits())
            assertEquals(a.longitude.toRawBits(), b.longitude.toRawBits())
            assertEquals(a.horizontalAccuracy.toRawBits(), b.horizontalAccuracy.toRawBits())
            assertEquals(a.altitude?.toRawBits(), b.altitude?.toRawBits())
            assertEquals(a.speed?.toRawBits(), b.speed?.toRawBits())
            assertEquals(a.stationary, b.stationary)
            assertEquals(a.speedAccuracy, b.speedAccuracy)
            assertEquals(a.course, b.course)
            assertEquals(a.courseAccuracy, b.courseAccuracy)
            assertEquals(a.verticalAccuracy, b.verticalAccuracy)
        }
    }
}
