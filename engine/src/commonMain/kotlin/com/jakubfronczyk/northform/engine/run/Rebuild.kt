package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.RecordingData
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * `Engine/Rebuild.swift` — rebuilds the run from what the store holds, for crash resume (D94).
 * Persisted samples and the user's own events are fed back through `reduce`, with 1 s ticks up to
 * the last flush. Derived events (splits, HR/GPS lost and back) are not fed: the reducer derives them.
 * At equal times, samples go before events (rank 0 < 1 < ticks 2), then input order.
 */
fun RunReducer.rebuild(config: RunConfig, data: RecordingData): RunState {
    val start = data.recording.startUtc
    var state = RunState(
        config,
        progress = RunProgress(phase = RunPhase.Active, startedAt = start),
        buffer = PersistBuffer(lastPersistAt = start),
    )

    class Item(val t: Instant, val rank: Int, val input: RunInput)
    val inputs = ArrayList<Item>()
    data.hr.forEach { inputs += Item(it.t, 0, RunInput.Hr(it)) }
    data.fixes.forEach { inputs += Item(it.t, 0, RunInput.Fix(it)) }
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
        if (input != null) inputs += Item(event.t, 1, input)
    }
    data.lastPersistedAt?.let { end ->
        var tick = start + 1.seconds
        while (tick < end) {
            inputs += Item(tick, 2, RunInput.Tick(tick))
            tick += 1.seconds
        }
        inputs += Item(end, 2, RunInput.Tick(end))
    }
    // sortedWith is stable, so equal (t, rank) keep input order — the Swift sort keyed on the offset too.
    for (item in inputs.sortedWith(compareBy({ it.t }, { it.rank }))) {
        state = reduce(state, item.input).state
    }
    return state.copy(buffer = PersistBuffer(lastPersistAt = data.lastPersistedAt ?: start))
}
