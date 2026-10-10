package com.jakubfronczyk.northform.engine.run

/**
 * Everything the lock-screen Live Activity shows, computed in Kotlin (D11). Primitives only, so the
 * Swift side maps fields 1:1 into `ContentState` without converting Kotlin types. Throttling (≤ 1 per
 * 5 s, immediate on phase change) is also Kotlin's job — the Swift controller just forwards.
 *
 * Any pace shown here is a SHOWN pace: a current pace goes through [ShownPace.round] first (D22), the
 * same function the run screen calls, so the lock screen and the screen never disagree.
 */
data class LiveActivityContent(
    val phase: LiveActivityPhase,
    val startedAtEpochMs: Long,
    val totalTimeSec: Long,
    val distanceM: Double,
    /** null = not shown yet (first 50 m of a split). */
    val splitPaceSecPerKm: Int?,
    val hrBpm: Int?,
    val hrLost: Boolean,
    val gpsLost: Boolean,
)

enum class LiveActivityPhase { Active, Paused, Done }
