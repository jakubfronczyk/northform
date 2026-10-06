package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.SensorState
import kotlin.time.Instant

/*
 * The reducer's vocabulary (D4): payload-carrying Swift enums become `sealed interface` +
 * `data class`/`data object`. `enum class` would not compile for these — its entries carry no
 * per-case payload. This is a SPIKE-SIZED subset to pin the shape; the full port (R24–R29) is Phase 0
 * step 2 / Phase 1 lane B.
 */

sealed interface RunInput {
    data class Hr(val sample: HrSample) : RunInput
    data class Fix(val fix: LocationFix) : RunInput
    data class Tick(val t: Instant) : RunInput
    data class Sensor(val state: SensorState, val at: Instant) : RunInput
    data class User(val action: UserAction, val at: Instant) : RunInput
    data class Backgrounded(val at: Instant) : RunInput
    /** The write loop confirms a batch by sequence number — sent INTO the mailbox, never a call back in (D5). */
    data class Persisted(val seq: Int) : RunInput
    data class PersistFailed(val seq: Int) : RunInput
}

/** Payload-free, so a real enum class is correct here (the only such type in the reducer). */
enum class UserAction { Start, Pause, Resume, Stop }

sealed interface RunEffect {
    data class Persist(val seq: Int, val batch: FlushBatch) : RunEffect
    data class Stop(val at: Instant) : RunEffect
    data class Alert(val kind: AlertKind) : RunEffect
}

enum class AlertKind { Split, HrLost }

sealed interface Phase {
    data object Gate : Phase
    data class Countdown(val endsAt: Instant) : Phase
    data class Active(val startedAt: Instant) : Phase
    data class Paused(val startedAt: Instant, val pausedAt: Instant) : Phase
    data class Done(val startedAt: Instant, val stoppedAt: Instant) : Phase
}

/** What one flush carries. `List`, never `Array`: data-class equality must be structural (D94 fold test). */
data class FlushBatch(val hr: List<HrSample>, val fixes: List<LocationFix>, val persistedAt: Instant)

/**
 * The whole run state as an immutable data class of `val`s. `reduce` returns a NEW state; equality
 * is the crash-resume contract (fold the store → compare with live).
 */
data class RunState(
    val phase: Phase = Phase.Gate,
    val hrNow: Int? = null,
    val lastHrAt: Instant? = null,
    val hrLost: Boolean = false,
    val buffer: FlushBatch? = null,
    val nextSeq: Int = 1,
    val inFlight: Int? = null,
    val unconfirmedHr: List<HrSample> = emptyList(),
    val unconfirmedFixes: List<LocationFix> = emptyList(),
    val lastFlushAt: Instant? = null,
)
