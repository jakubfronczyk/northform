package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.zone
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Previews only (D110, `RunView.swift:125`): a session that shows one snapshot and stays put; taps
 * change nothing. The screens depend on [RunSessionHandle], never on the real session. Tests use
 * [ScriptedFakeRunSession].
 */
class FakeRunSession(snapshot: RunSnapshot, route: Route = Route()) : RunSessionHandle {
    override val wallClock: WallClock = WallClock { Clock.System.now() }
    override val snapshots: StateFlow<RunSnapshot> = MutableStateFlow(snapshot)
    override val routes: StateFlow<Route> = MutableStateFlow(route)
    override suspend fun send(action: UserAction, at: Instant) {}
    override suspend fun end(): EndResult = EndResult.Discarded
    override suspend fun backgrounded(at: Instant) {}

    companion object {
        /** `LiveRunView.swift:207-218` — the preview values. */
        val previewRoute: Route = (0 until 40).fold(Route()) { route, i ->
            route + RoutePoint(52.5145 + i * 0.00003, 13.3501, startsSegment = i == 0)
        }

        val running = RunSnapshot(
            phase = RunPhase.Active, startedAt = Clock.System.now() - 2058.seconds, totalTime = 2058.seconds, distance = 6420.0,
            splitPace = 312.0, currentPace = 304.0, hrNow = 152, hrAvg = 148, hrMax = 171, zone = HRZones.standard.zone(3),
        )
    }
}

/**
 * A [RunSessionHandle] that follows the real phase order so a test can drive a screen Gate → Done
 * through the handle alone (run-flow-ui/05): `Start` at the gate opens the countdown, `Pause`/`Resume`,
 * `Stop` opens the cool-down, `SkipCooldown` ends it; [advanceTo] plays the session's 1 Hz tick (ends a
 * countdown or cool-down whose time came, grows the time). Out-of-order taps change nothing, like the
 * reducer. Sensor states are set by [script] so every artboard state is reachable. The clock is
 * injected: tests run it on `runTest`'s virtual scheduler, previews on the system clock.
 */
class ScriptedFakeRunSession(
    override val wallClock: WallClock,
    initial: RunSnapshot = RunSnapshot(RunPhase.Gate, hrLost = true, gpsLost = true),
    route: Route = Route(),
) : RunSessionHandle {
    private val state = MutableStateFlow(initial)
    private val routeState = MutableStateFlow(route)
    override val snapshots: StateFlow<RunSnapshot> get() = state
    override val routes: StateFlow<Route> get() = routeState

    /** Every tap in order, stamped — what a screen test asserts about its own sends. */
    val sent = ArrayList<Pair<UserAction, Instant>>()

    override suspend fun send(action: UserAction, at: Instant) {
        sent += action to at
        state.value = step(state.value, action, at)
    }

    /** D116: nothing recorded before the countdown ended → discarded; afterwards the run finishes. */
    override suspend fun end(): EndResult = if (state.value.startedAt == null) EndResult.Discarded else EndResult.Finishing

    override suspend fun backgrounded(at: Instant) {}

    /** The clock reached `now`: the timed phases move on, as the session's tick would make them. */
    fun advanceTo(now: Instant) {
        val s = state.value
        state.value = when (val phase = s.phase) {
            is RunPhase.Countdown -> if (now >= phase.endsAt) s.copy(phase = RunPhase.Active, startedAt = phase.endsAt, totalTime = now - phase.endsAt) else s
            RunPhase.Active -> s.copy(totalTime = now - (s.startedAt ?: now))
            is RunPhase.Paused -> s.copy(totalTime = now - (s.startedAt ?: now)) // wall time keeps running while paused
            is RunPhase.Cooldown -> if (now >= phase.until) s.copy(phase = RunPhase.Done) else s
            RunPhase.Gate, RunPhase.Done -> s
        }
    }

    /** Set what the sensors show (HR lost, GPS lost, a zone…): the test's stand-in for inputs. */
    fun script(change: RunSnapshot.() -> RunSnapshot) {
        state.value = state.value.change()
    }

    fun route(point: RoutePoint) {
        routeState.value += point
    }

    companion object {
        /** R24: the countdown. */
        val COUNTDOWN: Duration = 5.seconds
        /** H7: the cool-down. */
        val COOLDOWN: Duration = 60.seconds

        /** The phase table, pure: a tap in the wrong phase is ignored (`RunReducer` does the same). */
        fun step(s: RunSnapshot, action: UserAction, at: Instant): RunSnapshot = when (action) {
            UserAction.Start -> if (s.phase == RunPhase.Gate) s.copy(phase = RunPhase.Countdown(at + COUNTDOWN)) else s
            UserAction.Pause -> if (s.phase == RunPhase.Active) s.copy(phase = RunPhase.Paused(at), currentPace = null) else s
            UserAction.Resume -> if (s.phase is RunPhase.Paused) s.copy(phase = RunPhase.Active) else s
            UserAction.Stop -> if (s.phase == RunPhase.Active || s.phase is RunPhase.Paused) s.copy(phase = RunPhase.Cooldown(at + COOLDOWN), currentPace = null) else s
            UserAction.SkipCooldown -> if (s.phase is RunPhase.Cooldown) s.copy(phase = RunPhase.Done) else s
        }
    }
}
