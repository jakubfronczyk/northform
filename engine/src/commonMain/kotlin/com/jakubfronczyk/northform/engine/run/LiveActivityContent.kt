package com.jakubfronczyk.northform.engine.run

/**
 * Everything the lock-screen Live Activity shows, computed in Kotlin (D11). Primitives only, so the
 * Swift side maps fields 1:1 into `ContentState` without converting Kotlin types. Throttling (≤ 1 per
 * 5 s, immediate on phase change) is also Kotlin's job — the Swift controller just forwards.
 *
 * The pace is the SHOWN current pace (D38): [ShownPace.round] of `RunSnapshot.currentPace` (D22), the same
 * number the run screen shows biggest (D28), so the lock screen and the screen never disagree. Split pace is
 * not carried.
 */
data class LiveActivityContent(
    val phase: LiveActivityPhase,
    val startedAtEpochMs: Long,
    val totalTimeSec: Long,
    val distanceM: Double,
    /**
     * The shown current pace, s/km, a multiple of 5 (D22, D28, D38): always [shownPace] of the snapshot. null =
     * no current pace (paused, under 20 m, 15 s without counted distance: `RunSnapshot.currentPace`).
     */
    val paceSecPerKm: Int?,
    val hrBpm: Int?,
    val hrLost: Boolean,
    val gpsLost: Boolean,
) {
    companion object {
        /** D38: the pace field from a snapshot, through the run screen's own rounding (D22). */
        fun shownPace(snapshot: RunSnapshot): Int? = ShownPace.round(snapshot.currentPace)?.toInt()
    }
}

enum class LiveActivityPhase { Active, Paused, Done }
