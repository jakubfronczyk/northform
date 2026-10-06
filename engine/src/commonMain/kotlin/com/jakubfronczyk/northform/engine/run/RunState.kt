package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.roundedHalfAwayFromZero
import kotlin.time.Duration
import kotlin.time.Instant

// Ported from northform-ios `Engine/RunState.swift`. Swift value structs → Kotlin `data class`es of
// `val`s with `List`s (structural equality; the fold-equals-live test depends on it). The reducer
// builds the next state with `copy`.

/** `RunState.swift:5` — the run as the reducer sees it: `progress` (what happened) + `buffer` (what isn't durable yet). */
data class RunState(
    val config: RunConfig,
    val progress: RunProgress = RunProgress(),
    val buffer: PersistBuffer = PersistBuffer(),
) {
    val snapshot: RunSnapshot get() = progress.snapshot(config.profile)
}

/**
 * `RunState.swift:26` — everything that happened in the run. Rule (D94 of the Swift build): every
 * field can be rebuilt from the persisted recording by `RunReducer.rebuild`.
 */
data class RunProgress(
    val phase: RunPhase = RunPhase.Gate,
    val countdownTicksSent: Int = 0,
    val startedAt: Instant? = null,
    val stoppedAt: Instant? = null,
    val lastTick: Instant? = null,
    /** Paused time of finished pauses. The open one is in `phase`. */
    val pausedTotal: Duration = Duration.ZERO,
    // Heart
    val lastHr: HrSample? = null,
    val hrLost: Boolean = false,
    /** The alert for the current loss has played (D108). */
    val hrLostAlerted: Boolean = false,
    val hrSum: Int = 0,
    val hrCount: Int = 0,
    val hrMax: Int? = null,
    val sensorLost: Boolean = false,
    // GPS
    /** Latest good fix during the countdown; becomes the first stored fix at start. */
    val startFix: LocationFix? = null,
    /** Latest fix of any accuracy: for GPS-lost timing and out-of-order checks. */
    val lastFix: LocationFix? = null,
    val gpsLost: Boolean = false,
    /** GPS distance: smoothing, moving gate, bridging, route breaks (D107, D111). */
    val tracker: DistanceTracker = DistanceTracker(),
    val distance: Double = 0.0,
    /** How fast you run now (D106): fed the counted distance, cleared at pause and resume. */
    val currentPace: CurrentPace = CurrentPace(),
    // Splits
    val splitIndex: Int = 0,
    val splitStartActive: Duration = Duration.ZERO,
    val splitStartDistance: Double = 0.0,
) {
    /** `RunState.swift:63` — active time at `t`: wall time since start minus all pauses (for pace and splits). */
    fun activeTime(at: Instant): Duration {
        val startedAt = startedAt ?: return Duration.ZERO
        val end = stoppedAt?.let { minOf(it, at) } ?: at
        var paused = pausedTotal
        (phase as? RunPhase.Paused)?.let { paused += maxOf(Duration.ZERO, end - it.since) }
        return maxOf(Duration.ZERO, (end - startedAt) - paused)
    }

    /** `RunState.swift:71`. */
    fun snapshot(profile: Profile): RunSnapshot {
        val now = lastTick ?: startedAt
        val total = if (startedAt != null && now != null) maxOf(Duration.ZERO, (stoppedAt ?: now) - startedAt) else Duration.ZERO
        val splitDistance = distance - splitStartDistance
        val splitActive = (now?.let { activeTime(it) } ?: Duration.ZERO) - splitStartActive
        val hrNow = if (hrLost) null else lastHr?.bpm
        return RunSnapshot(
            phase = phase,
            startedAt = startedAt,
            totalTime = total,
            distance = distance,
            splitPace = if (splitDistance >= RunReducer.splitPaceMinDistance) splitActive.inSecondsDouble / splitDistance * 1000 else null,
            currentPace = currentPace.pace(now = lastTick, active = phase == RunPhase.Active),
            hrNow = hrNow,
            hrAvg = if (hrCount > 0) (hrSum.toDouble() / hrCount.toDouble()).roundedHalfAwayFromZero().toInt() else null,
            hrMax = hrMax,
            zone = hrNow?.let { profile.zone(it) },
            hrLost = hrLost,
            gpsLost = gpsLost,
        )
    }
}

/**
 * `RunState.swift:98` — samples and events not yet confirmed durable. Cleared only by
 * `Persisted(seq)`, so a failed write keeps them for the next try.
 */
data class PersistBuffer(
    val hr: List<HrSample> = emptyList(),
    val fixes: List<LocationFix> = emptyList(),
    val events: List<RecordingEvent> = emptyList(),
    val lastPersistAt: Instant? = null,
    /** The batch being written: its sequence number and how many of each it holds. */
    val inFlight: InFlight? = null,
    val nextSeq: Int = 1,
    /** A persist was asked for (pause, stop …) while another was in flight, and when. */
    val flushRequestedAt: Instant? = null,
    val computingRequested: Boolean = false,
) {
    data class InFlight(val seq: Int, val hr: Int, val fixes: Int, val events: Int)

    val isEmpty: Boolean get() = hr.isEmpty() && fixes.isEmpty() && events.isEmpty()
}
