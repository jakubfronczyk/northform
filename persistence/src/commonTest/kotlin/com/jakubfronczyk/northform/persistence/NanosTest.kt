package com.jakubfronczyk.northform.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NanosTest {
    private val t0 = Instant.parse("2026-10-10T06:00:00.123456Z")

    @Test
    fun nanos_round_trip_before_and_after_the_epoch() {
        for (i in listOf(Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(-1, 999_000_001), t0, Instant.fromEpochSeconds(4_000_000_000L, 1), Instant.parse("2026-01-01T00:11:47.851286177Z"))) {
            assertEquals(i, instantOfNanos(i.toNanos()), "$i")
        }
    }
}
