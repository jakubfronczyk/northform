package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.engine.run.RunInput
import kotlin.time.Instant

// Ported from northform-ios `Replay/ReplayScript.swift`.

/**
 * `ReplayScript.swift:6` — the setup of a replayed recording. Only what replay needs. `start` is
 * whole seconds only (the file stores ISO 8601, which has no fractions): the one way to build a
 * meta floors it, as the Swift init did.
 */
@ConsistentCopyVisibility
data class ReplayMeta private constructor(
    val type: RecordingType,
    val start: Instant,
    val hrMax: Int,
    val hrRest: Int,
    val sex: Sex,
) {
    companion object {
        operator fun invoke(type: RecordingType, start: Instant, hrMax: Int, hrRest: Int, sex: Sex) =
            ReplayMeta(type, Instant.fromEpochSeconds(start.epochSeconds), hrMax, hrRest, sex)
    }
}

/** `ReplayScript.swift:24` — one recorded input, in Core types, with absolute time. */
sealed interface ReplayInput {
    val t: Instant

    data class Hr(val sample: HrSample) : ReplayInput {
        override val t get() = sample.t
    }

    data class Fix(val fix: LocationFix) : ReplayInput {
        override val t get() = fix.t
    }

    data class Sensor(val state: SensorState, val at: Instant) : ReplayInput {
        override val t get() = at
    }

    data class User(val action: UserAction, val at: Instant) : ReplayInput {
        override val t get() = at
    }

    /** The same input as the reducer sees it. */
    fun toRunInput(): RunInput = when (this) {
        is Hr -> RunInput.Hr(sample)
        is Fix -> RunInput.Fix(fix)
        is Sensor -> RunInput.Sensor(state, at)
        is User -> RunInput.User(action, at)
    }
}

/** `ReplayScript.swift:41` — a whole recording to replay: setup plus inputs in time order. */
data class ReplayScript(val meta: ReplayMeta, val inputs: List<ReplayInput>) {
    val runInputs: List<RunInput> get() = inputs.map { it.toRunInput() }
}
