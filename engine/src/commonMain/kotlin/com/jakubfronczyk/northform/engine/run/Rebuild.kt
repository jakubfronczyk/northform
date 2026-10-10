package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.RecordingData
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Engine/Rebuild.swift` — rebuilds the run from what the store holds, for crash resume (D94).
 * Persisted samples and the user's own events are fed back through `reduce`, with 1 s ticks up to
 * the last flush. Derived events (splits, HR/GPS lost and back) are not fed: the reducer derives them.
 * Equal instants are ordered by [InputOrder]; the live stream keeps arrival order, so the "rebuild = live" tests judge this rule.
 */
fun RunReducer.rebuild(config: RunConfig, data: RecordingData): RunState {
    val start = data.recording.startUtc
    // The one start path (`RunProgress.begun`): the same state `RunReducer.begin` produces live, not a copy of it.
    var state = RunState(config, progress = RunProgress().begun(start), buffer = PersistBuffer().begun(start))

    val inputs = ArrayList<RunInput>()
    data.hr.forEach { inputs += RunInput.Hr(it) }
    data.fixes.forEach { inputs += RunInput.Fix(it) }
    for (event in data.events) {
        val input: RunInput? = when (val kind = event.kind) {
            EventKind.Pause -> RunInput.User(UserAction.Pause, event.t)
            EventKind.Resume -> RunInput.User(UserAction.Resume, event.t)
            EventKind.Stop -> RunInput.User(UserAction.Stop, event.t)
            EventKind.CooldownEnd -> if (event.source == EventSource.Manual) RunInput.User(UserAction.SkipCooldown, event.t) else null
            EventKind.SensorLost -> RunInput.Sensor(SensorState.Disconnected(DisconnectReason.Lost), event.t)
            EventKind.SensorReconnected -> RunInput.Sensor(SensorState.Connected(battery = null), event.t)
            is EventKind.Split, EventKind.HrLost, EventKind.HrBack, EventKind.GpsLost, EventKind.GpsBack, EventKind.CrashRecovered -> null
        }
        if (input != null) inputs += input
    }
    data.lastPersistedAt?.let { end ->
        var tick = start + 1.seconds
        while (tick < end) {
            inputs += RunInput.Tick(tick)
            tick += 1.seconds
        }
        inputs += RunInput.Tick(end)
    }
    for (input in InputOrder.sorted(inputs)) {
        state = reduce(state, input).state
    }
    return state.copy(buffer = PersistBuffer(lastPersistAt = data.lastPersistedAt ?: start))
}

/**
 * R28 "Continue": the run as it stands when the user reopens the app at [at], from a [rebuild] state.
 * The gap since the last heartbeat is 1 Hz ticks through the same pure reducer, so what the reducer
 * derives on a live run it derives here: HR lost 5 s after the last sample, GPS lost 10 s after the
 * last fix (unless it was stationary, D102), total time running, a paused run still paused. Nothing
 * is written: the `Persist` effects the ticks would have asked for are dropped and the in-flight
 * mark cleared; the HR-lost alert is swallowed too (the user caused this gap). `lastPersistAt` stays
 * the old heartbeat, so the session's first tick flushes at once — the derived events plus the
 * `CrashRecovered` event appended here (the reducer has no input for "the user chose Continue").
 */
fun RunReducer.resumed(rebuilt: RunState, at: Instant): RunState {
    val heartbeat = rebuilt.buffer.lastPersistAt ?: rebuilt.progress.startedAt ?: return rebuilt
    var state = rebuilt
    var tick = heartbeat + 1.seconds
    while (tick < at) {
        state = reduce(state, RunInput.Tick(tick)).state
        tick += 1.seconds
    }
    if (at > heartbeat) state = reduce(state, RunInput.Tick(at)).state
    val buffer = state.buffer
    return state.copy(
        buffer = buffer.copy(
            events = buffer.events + RecordingEvent(at, EventKind.CrashRecovered, EventSource.Auto),
            inFlight = null,
            flushRequestedAt = null,
            lastPersistAt = heartbeat,
        ),
    )
}
