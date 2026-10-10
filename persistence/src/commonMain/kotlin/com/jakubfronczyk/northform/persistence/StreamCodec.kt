package com.jakubfronczyk.northform.persistence

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import kotlin.time.Instant

/**
 * The byte layout of one `stream_chunk.payload` (D83: samples are chunked BLOBs inside SQLite, not
 * files). One chunk = one kind from one flush. Big-endian, fixed-width, no compression: a flush holds
 * a few seconds of samples, so a chunk is tens of bytes and the schema stays greppable with a hex dump.
 *
 * Every sample time is stored as a ns offset from the chunk's `t0` (the first sample), so a chunk can
 * be decoded on its own. Doubles are stored as their raw bits: what went in comes back bit for bit,
 * which the rebuild parity tests (D94) rely on.
 *
 * `hr.v1`  per sample: int64 Δt ns · uint16 bpm
 * `gps.v1` per fix:    int64 Δt ns · f64 lat · f64 lon · f64 horizontalAccuracy · uint8 mask · the f64s the mask marks present,
 *                      in this order: altitude, speed, speedAccuracy, course, courseAccuracy, verticalAccuracy.
 *                      mask bit 6 = stationary is known, bit 7 = stationary is true.
 */
internal object StreamCodec {
    const val HR = "hr.v1"
    const val GPS = "gps.v1"

    fun encodeHr(samples: List<HrSample>, t0: Instant): ByteArray {
        val out = ByteWriter(samples.size * 10)
        for (s in samples) {
            require(s.bpm in 0..0xFFFF) { "bpm out of range: ${s.bpm}" }
            out.long(s.t.toNanos() - t0.toNanos())
            out.short(s.bpm)
        }
        return out.bytes()
    }

    fun decodeHr(payload: ByteArray, t0: Instant, count: Int): List<HrSample> {
        val input = ByteReader(payload)
        val base = t0.toNanos()
        return List(count) { HrSample(instantOfNanos(base + input.long()), input.short()) }.also { input.expectExhausted(HR) }
    }

    fun encodeGps(fixes: List<LocationFix>, t0: Instant): ByteArray {
        val out = ByteWriter(fixes.size * 80)
        for (f in fixes) {
            out.long(f.t.toNanos() - t0.toNanos())
            out.double(f.latitude)
            out.double(f.longitude)
            out.double(f.horizontalAccuracy)
            val optional = listOf(f.altitude, f.speed, f.speedAccuracy, f.course, f.courseAccuracy, f.verticalAccuracy)
            var mask = 0
            optional.forEachIndexed { i, v -> if (v != null) mask = mask or (1 shl i) }
            if (f.stationary != null) mask = mask or (1 shl 6)
            if (f.stationary == true) mask = mask or (1 shl 7)
            out.byte(mask)
            optional.forEach { v -> if (v != null) out.double(v) }
        }
        return out.bytes()
    }

    fun decodeGps(payload: ByteArray, t0: Instant, count: Int): List<LocationFix> {
        val input = ByteReader(payload)
        val base = t0.toNanos()
        return List(count) {
            val t = instantOfNanos(base + input.long())
            val lat = input.double()
            val lon = input.double()
            val hacc = input.double()
            val mask = input.byte()
            fun optional(bit: Int): Double? = if (mask and (1 shl bit) != 0) input.double() else null
            LocationFix(
                t = t, latitude = lat, longitude = lon, horizontalAccuracy = hacc,
                altitude = optional(0), speed = optional(1), speedAccuracy = optional(2),
                course = optional(3), courseAccuracy = optional(4), verticalAccuracy = optional(5),
                stationary = if (mask and (1 shl 6) != 0) mask and (1 shl 7) != 0 else null,
            )
        }.also { input.expectExhausted(GPS) }
    }
}

private class ByteWriter(capacity: Int) {
    private var buf = ByteArray(maxOf(capacity, 16))
    private var size = 0

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }
    fun short(v: Int) { ensure(2); buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte() }
    fun long(v: Long) { ensure(8); for (shift in 56 downTo 0 step 8) buf[size++] = (v ushr shift).toByte() }
    fun double(v: Double) = long(v.toRawBits())
    fun bytes(): ByteArray = buf.copyOf(size)
}

private class ByteReader(private val buf: ByteArray) {
    private var pos = 0

    fun byte(): Int = buf[pos++].toInt() and 0xFF
    fun short(): Int = (byte() shl 8) or byte()
    fun long(): Long { var v = 0L; repeat(8) { v = (v shl 8) or byte().toLong() }; return v }
    fun double(): Double = Double.fromBits(long())
    /** A chunk whose `count` and payload disagree is corrupt: say so instead of returning a truncated or garbage sample. */
    fun expectExhausted(codec: String) = check(pos == buf.size) { "$codec chunk: $pos of ${buf.size} bytes used" }
}
