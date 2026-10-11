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
            EventKind.BluetoothOff -> RunInput.Sensor(SensorState.BluetoothOff, event.t) // D31
            is EventKind.Split, EventKind.HrLost, EventKind.HrBack, EventKind.GpsLost, EventKind.GpsBack, EventKind.CrashRecovered -> null
        }
        if (input != null) inputs += input
    }
    data.lastPersistedAt?.let { end ->
        ticks(after = start, before = end).forEach { inputs += RunInput.Tick(it) }
        inputs += RunInput.Tick(end)
    }
    // The latest HR/GPS lost-or-back event rebuild itself derived, per signal (see `notStored`).
    val derived = HashMap<Signal, RecordingEvent>()
    for (input in InputOrder.sorted(inputs)) {
        val next = reduce(state, input).state
        for (event in next.buffer.events) Signal.of(event.kind)?.let { derived[it] = event }
        // Nothing here answers a `Persist`, so the buffer would hold every sample of the run and each
        // append (`b.hr + sample`) would copy it: O(n²). Its contents never reach `progress` (only the
        // persist bookkeeping reads them) and the buffer is replaced below, so it is emptied as we go.
        state = next.copy(buffer = next.buffer.copy(hr = emptyList(), fixes = emptyList(), events = emptyList()))
    }
    val progress = state.progress
    val notStored = listOfNotNull(
        notStored(Signal.Hr, lost = progress.hrLost, data.events, derived),
        notStored(Signal.Gps, lost = progress.gpsLost, data.events, derived),
    ).sortedBy { it.t }
    // D35: rebuild's ticks need not land where the live ones did, so the loss starts where the live run said it
    // did — the stored `HrLost` still open — else at the one rebuild derived (and keeps unstored, above).
    val hrLostSince = if (!progress.hrLost) null else {
        val open = data.events.lastOrNull { Signal.of(it.kind) == Signal.Hr }?.takeIf { it.kind == EventKind.HrLost }
        (open ?: derived[Signal.Hr])?.t ?: progress.hrLostSince
    }
    return state.copy(
        progress = progress.copy(hrLostSince = hrLostSince),
        buffer = PersistBuffer(events = notStored, lastPersistAt = data.lastPersistedAt ?: start),
    )
}

/** HR and GPS each derive a lost/back pair; the flag the reducer keeps is whichever came last. */
private enum class Signal(val lost: EventKind, val back: EventKind) {
    Hr(EventKind.HrLost, EventKind.HrBack),
    Gps(EventKind.GpsLost, EventKind.GpsBack);

    companion object {
        fun of(kind: EventKind): Signal? = entries.firstOrNull { kind == it.lost || kind == it.back }
    }
}

/**
 * Rebuild's 1 s ticks need not land where the live ticks did, so its closing tick can derive a loss
 * the live run never reached before the last flush (backgrounded 0.6 s after a tick, say). The
 * rebuilt flag then says "lost" while the store holds no HrLost. Dropping that event would leave the
 * flag set with nothing durable behind it: `resumed` never derives it again, a later HrBack closes a
 * loss the store never opened, and reliability misses the gap. So when the rebuilt flag disagrees
 * with the last stored event of its signal, the event rebuild derived for it stays in the buffer —
 * not durable yet — and the first flush after Continue writes it.
 */
private fun notStored(signal: Signal, lost: Boolean, stored: List<RecordingEvent>, derived: Map<Signal, RecordingEvent>): RecordingEvent? {
    val storedLost = stored.lastOrNull { Signal.of(it.kind) == signal }?.kind == signal.lost
    if (lost == storedLost) return null
    return derived[signal]?.takeIf { it.kind == (if (lost) signal.lost else signal.back) }
}

/**
 * D111: the distance [rebuild] would count, without replaying the heart rate, the sensor and the
 * ticks — none of them moves the tracker. Fixes and the user's Pause / Resume / Stop, ordered by
 * [InputOrder], go through the reducer's own tracker calls under the reducer's own guards: a late fix
 * is dropped, fixes after the stop are ignored, a pause or stop while running counts the lag
 * (`flushLag`), a resume restarts the smoothing. `RunSummaryTest` holds it equal to [rebuild].
 */
fun RunReducer.distance(data: RecordingData): Double {
    val inputs = ArrayList<RunInput>()
    data.fixes.forEach { inputs += RunInput.Fix(it) }
    for (event in data.events) when (event.kind) {
        EventKind.Pause -> inputs += RunInput.User(UserAction.Pause, event.t)
        EventKind.Resume -> inputs += RunInput.User(UserAction.Resume, event.t)
        EventKind.Stop -> inputs += RunInput.User(UserAction.Stop, event.t)
        else -> Unit
    }
    var tracker = DistanceTracker()
    var active = true
    var stopped = false
    var lastFixAt: Instant? = null
    var distance = 0.0
    fun count(step: DistanceTracker.Step) {
        tracker = step.tracker
        step.segment?.let { distance += it.metres }
    }
    for (input in InputOrder.sorted(inputs)) when (input) {
        is RunInput.Fix -> {
            val fix = input.fix
            if (lastFixAt?.let { fix.t < it } == true || stopped) continue
            lastFixAt = fix.t
            count(tracker.add(fix, counting = active, lostAfter = gpsLostAfter, lostAccuracy = gpsLostAccuracy))
        }
        is RunInput.User -> when {
            stopped -> Unit
            input.action == UserAction.Pause && active -> { count(tracker.flushLag()); active = false }
            input.action == UserAction.Resume && !active -> { tracker = tracker.resume(); active = true }
            input.action == UserAction.Stop -> { if (active) count(tracker.flushLag()); stopped = true }
        }
        else -> Unit
    }
    return distance
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
    ticks(after = heartbeat, before = at).forEach { state = reduce(state, RunInput.Tick(it)).state }
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

/**
 * The 1 Hz heartbeat the store did not keep: [after] + 1 s, + 2 s … strictly before [before]. Each
 * caller adds its own closing tick ([rebuild] always at the last flush, [resumed] only when time moved).
 */
private fun ticks(after: Instant, before: Instant): Sequence<Instant> =
    generateSequence(after + 1.seconds) { it + 1.seconds }.takeWhile { it < before }
