package com.jakubfronczyk.northform.core

import kotlin.jvm.JvmInline
import kotlin.time.Instant

// Ported 1:1 from northform-ios `Core/Model/{Recording,RecordingEvent,UserAction,MetricResult}.swift`.
// Swift `struct` wrappers → Kotlin `value class`; Swift string-raw-value enums → `enum class` with
// a `wire` name (what the fixture format and the store write).

/** `Recording.swift:3` — a UUID string; the engine never mints one (the session does, D94). */
@JvmInline
value class RecordingId(val value: String)

/** `Recording.swift:12` — one user today; every recording carries it so more can be added later. */
@JvmInline
value class UserId(val value: Int) {
    companion object {
        val me = UserId(1)
    }
}

/** `Recording.swift:23` — v1.0 types only. */
enum class RecordingType(val wire: String) {
    Run("run"), Walk("walk");

    companion object {
        fun fromWire(wire: String): RecordingType? = entries.firstOrNull { it.wire == wire }
    }
}

/** `Recording.swift:29` — suggested from zones and time, editable. Walks have none. */
enum class RunTag(val wire: String) { Easy("easy"), Long("long"), Tempo("tempo"), Intervals("intervals") }

/** `Recording.swift:36` — live state machine: active → stopping → computing → saved. */
enum class RecordingState(val wire: String) { Active("active"), Stopping("stopping"), Computing("computing"), Saved("saved") }

/** `Recording.swift:45`. */
data class Recording(
    val id: RecordingId,
    val userId: UserId = UserId.me,
    val type: RecordingType,
    val tag: RunTag? = null,
    val state: RecordingState,
    val startUtc: Instant,
    /** Seconds east of UTC at start. */
    val tzOffset: Int,
    /** The moment of hold-to-stop. Cool-down samples come after it. */
    val endUtc: Instant? = null,
    /** Rate of perceived exertion, 0–10. */
    val rpe: Int? = null,
    val notes: String? = null,
)

/** `RecordingEvent.swift:3`. */
enum class EventSource(val wire: String) { Auto("auto"), Manual("manual") }

/**
 * `RecordingEvent.swift:9` — v1.0 event kinds. `Split` carries its index, so the hierarchy is a
 * sealed interface (D4), not an enum class.
 */
sealed interface EventKind {
    data object Pause : EventKind
    data object Resume : EventKind
    /** Every 1 km; `index` starts at 1. */
    data class Split(val index: Int) : EventKind
    /** No heart-rate sample for 5 s. */
    data object HrLost : EventKind
    data object HrBack : EventKind
    /** No GPS fix for 10 s, or accuracy worse than 50 m. */
    data object GpsLost : EventKind
    data object GpsBack : EventKind
    /** Bluetooth link dropped / came back. Counted in the reliability log. */
    data object SensorLost : EventKind
    data object SensorReconnected : EventKind
    /** Hold-to-stop. Written only by `RecordingStore.stop`, together with `Recording.endUtc`. */
    data object Stop : EventKind
    /** End of the 60 s heart-rate cool-down, or the skip. */
    data object CooldownEnd : EventKind
    /** App reopened after a crash and the user chose Continue. */
    data object CrashRecovered : EventKind
}

/** The kind's stored name (what the Swift description printed and the store will write); `Split` keeps its index separately. */
val EventKind.wire: String
    get() = when (this) {
        EventKind.Pause -> "pause"
        EventKind.Resume -> "resume"
        is EventKind.Split -> "split"
        EventKind.HrLost -> "hrLost"
        EventKind.HrBack -> "hrBack"
        EventKind.GpsLost -> "gpsLost"
        EventKind.GpsBack -> "gpsBack"
        EventKind.SensorLost -> "sensorLost"
        EventKind.SensorReconnected -> "sensorReconnected"
        EventKind.Stop -> "stop"
        EventKind.CooldownEnd -> "cooldownEnd"
        EventKind.CrashRecovered -> "crashRecovered"
    }

/** `RecordingEvent.swift:31`. */
data class RecordingEvent(val t: Instant, val kind: EventKind, val source: EventSource)

/** `UserAction.swift:2` — what the user can do during a run. Shared by the reducer and the fixtures. */
enum class UserAction(val wire: String) {
    Start("start"), Pause("pause"), Resume("resume"),
    /** Hold-to-stop. */
    Stop("stop"),
    /** Ends the 60 s heart-rate cool-down early. */
    SkipCooldown("skipCooldown");

    companion object {
        fun fromWire(wire: String): UserAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** `MetricResult.swift`. Scalars only; splits are recomputed from the raw streams. */
@JvmInline
value class MetricId(val value: String)

data class MetricResult(val metricId: MetricId, val version: Int, val value: Double, val profile: Profile)
