package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.FlushBatch
import kotlin.time.Duration
import kotlin.time.Instant

// Ported from northform-ios `Engine/RunTypes.swift`. Every Swift enum with associated values is a
// `sealed interface` with `data class`/`data object` members (D4); payload-free ones stay enums.

/** `RunTypes.swift:6` — fixed for the whole run, decided once by the session. */
data class RunConfig(
    val recordingId: RecordingId,
    val type: RecordingType,
    val profile: Profile,
    /** Seconds east of UTC at start. */
    val tzOffset: Int,
)

/** `RunTypes.swift:21`. */
sealed interface RunPhase {
    data object Gate : RunPhase
    data class Countdown(val endsAt: Instant) : RunPhase
    data object Active : RunPhase
    data class Paused(val since: Instant) : RunPhase
    data class Cooldown(val until: Instant) : RunPhase
    data object Done : RunPhase
}

/** `RunTypes.swift:31` — everything that can happen to a run. Each input carries its own time. */
sealed interface RunInput {
    data class Hr(val sample: HrSample) : RunInput
    data class Fix(val fix: LocationFix) : RunInput
    data class Tick(val t: Instant) : RunInput
    data class Sensor(val state: SensorState, val at: Instant) : RunInput
    data class User(val action: UserAction, val at: Instant) : RunInput
    /** The app went to the background: persist now. */
    data class Backgrounded(val at: Instant) : RunInput
    /** The store confirmed batch `seq`: those samples and events are durable. */
    data class Persisted(val seq: Int) : RunInput
    /** Writing batch `seq` failed: its data stays in the buffer and is retried. */
    data class PersistFailed(val seq: Int) : RunInput
}

/** The time a timed input carries; store confirmations have none. */
val RunInput.time: Instant?
    get() = when (this) {
        is RunInput.Hr -> sample.t
        is RunInput.Fix -> fix.t
        is RunInput.Tick -> t
        is RunInput.Sensor -> at
        is RunInput.User -> at
        is RunInput.Backgrounded -> at
        is RunInput.Persisted, is RunInput.PersistFailed -> null
    }

/**
 * `RunTypes.swift:46` — what the session must do, in order. [Store] effects go to the store writer,
 * strictly ordered; the others happen at once. The split is in the type so no `when` needs an
 * "impossible" branch.
 */
sealed interface RunEffect {
    sealed interface Store : RunEffect

    /** Countdown over: write the first row. */
    data class Create(val recording: Recording) : Store
    /** Write one batch; answer with `Persisted(seq)` or `PersistFailed(seq)`. */
    data class Persist(val seq: Int, val batch: FlushBatch) : Store
    /** Hold-to-stop: the store writes `endUtc` and the `Stop` event together. */
    data class Stop(val at: Instant) : Store
    /** Cool-down over and everything persisted: state → computing. */
    data object BeginComputing : Store

    data class PlayAlert(val alert: Alert) : RunEffect
    /** A fix that counted for distance: the map draws exactly what was counted. */
    data class Route(val point: RoutePoint) : RunEffect
}

/**
 * `RunTypes.swift:62` — one point of the drawn route. `startsSegment` = lift the pen first (start,
 * after a pause, after a GPS gap that wasn't bridged).
 */
data class RoutePoint(val latitude: Double, val longitude: Double, val startsSegment: Boolean) {
    constructor(fix: LocationFix, startsSegment: Boolean) : this(fix.latitude, fix.longitude, startsSegment)
}

/** `RunTypes.swift:79` — the whole drawn route, as segments. Immutable: `plus` returns the next route. */
data class Route(val segments: List<List<RoutePoint>> = emptyList()) {
    operator fun plus(point: RoutePoint): Route =
        if (point.startsSegment || segments.isEmpty()) Route(segments + listOf(listOf(point)))
        else Route(segments.dropLast(1) + listOf(segments.last() + point))
}

/** `RunTypes.swift:94` — what the run screen and the Live Activity show. A view of `RunState`. */
data class RunSnapshot(
    val phase: RunPhase,
    val startedAt: Instant? = null,
    /** Wall time since start, pauses included (the main time). Frozen at stop. */
    val totalTime: Duration = Duration.ZERO,
    /** Metres, paused movement excluded. */
    val distance: Double = 0.0,
    /** Seconds per km for the current km so far. */
    val splitPace: Double? = null,
    /** How fast you run now, seconds per km (D106). null while paused, after 15 s without counted distance, or under 20 m. */
    val currentPace: Double? = null,
    /** null while heart rate is lost: never show a stale value. */
    val hrNow: Int? = null,
    val hrAvg: Int? = null,
    val hrMax: Int? = null,
    val zone: Int? = null,
    val hrLost: Boolean = false,
    val gpsLost: Boolean = false,
)
