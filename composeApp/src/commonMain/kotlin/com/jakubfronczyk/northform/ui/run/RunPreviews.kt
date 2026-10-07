package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.Theme
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

// One preview per run state, from plain values (`RunView.swift:152-179`, `LiveRunView.swift:220-242`).

/** A flat placeholder where the platform map goes. */
@Composable
private fun PreviewMap(route: Route) {
    Box(Modifier.fillMaxSize().background(Theme.track))
}

@Composable
private fun previewRun(snapshot: RunSnapshot) {
    RunFlow(FakeRunSession(snapshot, FakeRunSession.previewRoute), onClose = {}, map = { r -> PreviewMap(r) })
}

@Preview @Composable fun GatePreview() = previewRun(RunSnapshot(RunPhase.Gate))
@Preview @Composable fun CountdownPreview() = previewRun(RunSnapshot(RunPhase.Countdown(Clock.System.now() + 4.seconds)))
@Preview @Composable fun ActivePreview() = previewRun(FakeRunSession.running)
@Preview @Composable fun PausedPreview() = previewRun(FakeRunSession.running.copy(phase = RunPhase.Paused(Clock.System.now()), currentPace = null))
@Preview @Composable fun HrLostPreview() = previewRun(FakeRunSession.running.copy(hrLost = true, hrNow = null, zone = null))
@Preview @Composable fun GpsLostPreview() = previewRun(FakeRunSession.running.copy(gpsLost = true))
@Preview @Composable fun CooldownPreview() = previewRun(FakeRunSession.running.copy(phase = RunPhase.Cooldown(Clock.System.now() + 45.seconds)))
@Preview @Composable fun DonePreview() = previewRun(FakeRunSession.running.copy(phase = RunPhase.Done))
