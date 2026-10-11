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
     * The shown current pace, s/km, a multiple of 5 (D22, D28, D38): [ShownPace.round] of the snapshot's, as [of] fills it. null =
     * no current pace (paused, under 20 m, 15 s without counted distance: `RunSnapshot.currentPace`).
     */
    val paceSecPerKm: Int?,
    val hrBpm: Int?,
    val hrLost: Boolean,
    val gpsLost: Boolean,
) {
    companion object {
        /**
         * The one way to build the content: every field from the run's snapshot, the pace through the run screen's own
         * rounding (D22, D38). null before the run starts (gate, countdown): there is no Live Activity yet. The
         * cool-down is `Done`: hold-to-stop ended the run, the lock screen stops counting with it.
         */
        fun of(snapshot: RunSnapshot): LiveActivityContent? {
            val startedAt = snapshot.startedAt ?: return null
            val phase = when (snapshot.phase) {
                RunPhase.Gate, is RunPhase.Countdown -> return null
                RunPhase.Active -> LiveActivityPhase.Active
                is RunPhase.Paused -> LiveActivityPhase.Paused
                is RunPhase.Cooldown, RunPhase.Done -> LiveActivityPhase.Done
            }
            return LiveActivityContent(
                phase = phase,
                startedAtEpochMs = startedAt.toEpochMilliseconds(),
                totalTimeSec = snapshot.totalTime.inWholeSeconds,
                distanceM = snapshot.distance,
                paceSecPerKm = ShownPace.round(snapshot.currentPace)?.toInt(),
                hrBpm = snapshot.hrNow,
                hrLost = snapshot.hrLost,
                gpsLost = snapshot.gpsLost,
            )
        }
    }
}

enum class LiveActivityPhase { Active, Paused, Done }
