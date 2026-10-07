package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Previews and screen tests only (D110, `RunView.swift:125`): a session that shows one snapshot and
 * stays put; taps change nothing. The screens depend on [RunSessionHandle], never on the real session.
 */
class FakeRunSession(snapshot: RunSnapshot, route: Route = Route()) : RunSessionHandle {
    override val wallClock: WallClock = WallClock { Clock.System.now() }
    override val snapshots: StateFlow<RunSnapshot> = MutableStateFlow(snapshot)
    override val routes: StateFlow<Route> = MutableStateFlow(route)
    override suspend fun send(action: UserAction, at: Instant) {}

    companion object {
        /** `LiveRunView.swift:207-218` — the preview values. */
        val previewRoute: Route = (0 until 40).fold(Route()) { route, i ->
            route + RoutePoint(52.5145 + i * 0.00003, 13.3501, startsSegment = i == 0)
        }

        val running = RunSnapshot(
            phase = RunPhase.Active, startedAt = Clock.System.now() - 2058.seconds, totalTime = 2058.seconds, distance = 6420.0,
            splitPace = 312.0, currentPace = 304.0, hrNow = 152, hrAvg = 148, hrMax = 171, zone = 3,
        )
    }
}
