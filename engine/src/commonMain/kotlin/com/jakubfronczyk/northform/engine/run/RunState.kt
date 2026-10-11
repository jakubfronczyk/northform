package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.metrics.HeartRate
import com.jakubfronczyk.northform.core.metrics.Splits
import com.jakubfronczyk.northform.core.metrics.zoneOf
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
    val snapshot: RunSnapshot get() = progress.snapshot(config)
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
    /** D35: the moment [hrLost] turned true (its `HrLost` event's time); null while it is false. */
    val hrLostSince: Instant? = null,
    /** The alert for the current loss has played (D108). */
    val hrLostAlerted: Boolean = false,
    val hrSum: Int = 0,
    val hrCount: Int = 0,
    val hrMax: Int? = null,
    val sensorLost: Boolean = false,
    // Start gate (R24, D20): what the gate has seen BEFORE the run starts. Seen, not recorded: never
    // stored, never averaged, cleared by `begun`. The snapshot turns it into HR ✓ / GPS ✓.
    /** Latest valid sample before start: HR ✓ while it is younger than `RunReducer.hrLostAfter` (aged by the tick). */
    val gateHr: HrSample? = null,
    /** Consecutive fixes ≤ `RunReducer.gateFixAccuracy` before start: GPS ✓ from `RunReducer.gateFixes` on. */
    val gateGoodFixes: Int = 0,
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

    /**
     * `RunReducer.swift:210` — the moment the countdown ends. The ONE start path: the live reducer's
     * `begin` and `rebuild` both go through here, so a resumed run starts from the same state as a
     * live one (D94). What the gate saw ends here.
     */
    fun begun(at: Instant): RunProgress =
        copy(phase = RunPhase.Active, startedAt = at, countdownTicksSent = 0, gateHr = null, gateGoodFixes = 0)

    /**
     * R24 before the run starts (`Gate` and `Countdown`): HR ✓ = a valid sample in the last 5 s (the
     * same 5 s as HR lost), GPS ✓ = 3 consecutive fixes ≤ 20 m. Aged by `lastTick`; a sample newer
     * than the last tick is fresh by definition.
     */
    val gateHrReady: Boolean
        get() = gateHr?.let { sample -> maxOf(lastTick ?: sample.t, sample.t) - sample.t < RunReducer.hrLostAfter } ?: false
    val gateGpsReady: Boolean get() = gateGoodFixes >= RunReducer.gateFixes

    /**
     * `RunState.swift:71`. Before start the snapshot carries the gate's readiness in the fields the
     * screens already read: `hrLost`/`gpsLost` = not ready (R24), `hrNow` = the live bpm. After start
     * they mean what they always meant (R25, R26).
     */
    fun snapshot(config: RunConfig): RunSnapshot {
        val profile = config.profile
        val now = lastTick ?: startedAt
        val total = if (startedAt != null && now != null) maxOf(Duration.ZERO, (stoppedAt ?: now) - startedAt) else Duration.ZERO
        val splitDistance = distance - splitStartDistance
        val splitActive = (now?.let { activeTime(it) } ?: Duration.ZERO) - splitStartActive
        val atTheGate = startedAt == null
        val hrNow = when {
            atTheGate -> if (gateHrReady) gateHr?.bpm else null
            hrLost -> null
            else -> lastHr?.bpm
        }
        return RunSnapshot(
            phase = phase,
            startedAt = startedAt,
            totalTime = total,
            distance = distance,
            splitPace = Splits.pace(splitActive, splitDistance),
            currentPace = currentPace.pace(now = lastTick, active = phase == RunPhase.Active),
            hrNow = hrNow,
            hrAvg = HeartRate.mean(hrSum, hrCount),
            hrMax = hrMax,
            zone = hrNow?.let { profile.zoneOf(it) },
            hrLost = if (atTheGate) !gateHrReady else hrLost,
            gpsLost = if (atTheGate) !gateGpsReady else gpsLost,
            hrLostSince = if (atTheGate) null else hrLostSince,
            type = config.type,
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

    /** The buffer at the moment the run starts (the one start path, see `RunProgress.begun`): the 5 s persist clock starts now. */
    fun begun(at: Instant): PersistBuffer = copy(lastPersistAt = at)

    val isEmpty: Boolean get() = hr.isEmpty() && fixes.isEmpty() && events.isEmpty()
}
