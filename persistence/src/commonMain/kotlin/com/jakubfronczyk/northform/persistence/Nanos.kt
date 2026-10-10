package com.jakubfronczyk.northform.persistence

import kotlin.time.Instant

// Column time = epoch nanoseconds in a 64-bit INTEGER: exactly what `Instant` carries, so a stored time
// is the time the reducer saw (a split is interpolated between two fixes and lands on a nanosecond).
// A signed 64-bit nanosecond count reaches the year 2262; the schema will be migrated before then.

internal fun Instant.toNanos(): Long = epochSeconds * 1_000_000_000 + nanosecondsOfSecond

internal fun instantOfNanos(nanos: Long): Instant =
    Instant.fromEpochSeconds(nanos.floorDiv(1_000_000_000), nanos.mod(1_000_000_000))
