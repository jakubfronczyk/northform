package com.jakubfronczyk.northform.ui.run

import androidx.compose.runtime.Composable
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.engine.run.SummaryEdits
import com.jakubfronczyk.northform.ui.SetupPermissions
import com.jakubfronczyk.northform.engine.run.SummarySession
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

// One preview per run state, from plain values (`RunView.swift:152-179`, `LiveRunView.swift:220-242`).

/** Previews draw the Compose route (`RouteCanvas`); the platform map is injected only by `:app`. */
@Composable
private fun previewRun(snapshot: RunSnapshot) {
    RunFlow(FakeRunSession(snapshot, FakeRunSession.previewRoute), onClose = {}, map = { r -> RouteCanvas(r) }, gateSensor = null, connectSensor = null)
}

// Gate (R24/E1): one preview per artboard state. The initial snapshot has nothing seen yet.
private val gate = RunSnapshot(RunPhase.Gate, hrLost = true, gpsLost = true)
@Composable
private fun previewGate(snapshot: RunSnapshot, sensor: GateSensor? = null, location: SetupPermissions.Location? = null) {
    RunFlow(FakeRunSession(snapshot), onClose = {}, map = { r -> RouteCanvas(r) }, gateSensor = sensor, connectSensor = {}, gateLocation = location, openLocationSettings = {})
}
// run-flow-ui/07: location off for the app (with Open Settings) and restricted by the phone (without): GPS can never lock.
@Preview @Composable fun GateLocationDeniedPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74), GateSensor(true, 82, paired = true, name = "Verity Sense"), SetupPermissions.Location.Denied)
/** The tallest gate: location denied (reason + Open Settings) with a low sensor battery and its warning: the 16e fit check. */
@Preview @Composable fun GateLocationDeniedLowBatteryPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74), GateSensor(true, 14, paired = true, name = "Verity Sense"), SetupPermissions.Location.Denied)
@Preview @Composable fun GateLocationRestrictedPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74), GateSensor(true, 14, paired = true, name = "Verity Sense"), SetupPermissions.Location.Restricted)
@Preview @Composable fun GateWaitingPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74), GateSensor(true, 82, paired = true, name = "Verity Sense"))
@Preview @Composable fun GateWaitingForHrPreview() = previewGate(gate.copy(gpsLost = false))
@Preview @Composable fun GateNothingYetPreview() = previewGate(gate)
@Preview @Composable fun GateReadyPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74, gpsLost = false), GateSensor(true, 82, paired = true, name = "Verity Sense"))
@Preview @Composable fun GateNoSensorPreview() = previewGate(gate.copy(gpsLost = false), GateSensor(false, null, paired = true))
@Preview @Composable fun GateLowBatteryPreview() = previewGate(gate.copy(hrLost = false, hrNow = 74, gpsLost = false), GateSensor(true, 14, paired = true, name = "Verity Sense"))
@Preview @Composable fun GateNothingPairedPreview() = previewGate(gate.copy(gpsLost = false), GateSensor(false, null, paired = false))
// Countdown (run-flow-artboards): a run's first second (5, no dots), the artboard's "3", and a walk's grey chip at 1.
@Preview @Composable fun CountdownFivePreview() = previewRun(RunSnapshot(RunPhase.Countdown(Clock.System.now() + 4.9.seconds)))
@Preview @Composable fun CountdownPreview() = previewRun(RunSnapshot(RunPhase.Countdown(Clock.System.now() + 2.5.seconds)))
@Preview @Composable fun CountdownWalkPreview() = previewRun(RunSnapshot(RunPhase.Countdown(Clock.System.now() + 0.5.seconds), type = RecordingType.Walk))
@Preview @Composable fun ActivePreview() = previewRun(FakeRunSession.running)
@Preview @Composable fun PausedPreview() = previewRun(FakeRunSession.running.copy(phase = RunPhase.Paused(Clock.System.now()), currentPace = null))
/** Sensor dropped mid-run: HR now is "—", the averages so far stay (H1). */
@Preview @Composable fun HrLostPreview() = previewRun(FakeRunSession.running.copy(hrLost = true, hrNow = null, zone = null))
/** No sample yet in this run: all three HR numbers are "—". */
@Preview @Composable fun NoHrYetPreview() = previewRun(FakeRunSession.running.copy(hrLost = true, hrNow = null, hrAvg = null, hrMax = null, zone = null))
@Preview @Composable fun GpsLostPreview() = previewRun(FakeRunSession.running.copy(gpsLost = true))
// Cool-down (run-flow-artboards): the artboard's two modes at 0:22 (172 at the stop, 151 now), and the screen opened
// late through the flow (its trace missed the stop: "Started at" and the drop are "—").
private val cooldownStop = Clock.System.now() - 22.seconds
private val cooling = FakeRunSession.running.copy(phase = RunPhase.Cooldown(cooldownStop + 60.seconds), totalTime = 3160.seconds, distance = 10_210.0, hrNow = 151, currentPace = null)
private val cooldownTrace = CooldownTrace(List(23) { s -> 172 - (21 * s / 22) })
@Preview @Composable fun CooldownPreview() = CooldownContent(CooldownModel.from(cooling, cooldownStop + 22.seconds, cooldownTrace), skip = {})
@Preview @Composable fun CooldownSensorLostPreview() = CooldownContent(
    CooldownModel.from(cooling.copy(hrLost = true, hrNow = null), cooldownStop + 22.seconds, CooldownTrace(cooldownTrace.points.take(15) + List(8) { null })),
    skip = {},
)
@Preview @Composable fun CooldownJoinedLatePreview() = previewRun(cooling)
// Summary (R27): one preview per artboard state, from the FakeSummary fixture (summary-screen/01).
private val previewEdits = SummaryEdits(tag = RunTag.Tempo, rpe = 7, notes = null)
@Preview @Composable fun SummaryComputingPreview() = SummaryScreen(SummarySession.State.Computing, edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummaryRunPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.run), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummaryWalkPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.walk), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummarySaveFailedPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.run, error = "disk full"), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummarySkippedCooldownPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.skippedCooldown), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummaryNewHrMaxPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.newHrMax), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
@Preview @Composable fun SummaryFailedPreview() = SummaryScreen(SummarySession.State.Failed("database locked"), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
// history-screen/03: reopened from History (`SummaryReview`): read-only, no Save, Back.
@Preview @Composable fun SummaryReviewRunPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.newHrMax), edits = SummaryEdits(RunTag.Tempo, 7, "windy on the bridge"), onEdit = {}, onSave = {}, onDelete = {}, review = true, onBack = {})
@Preview @Composable fun SummaryReviewWalkPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.walk), edits = SummaryEdits(null, 3, null), onEdit = {}, onSave = {}, onDelete = {}, review = true, onBack = {})
@Preview @Composable fun SummarySavingPreview() = SummaryScreen(SummarySession.State.Ready(FakeSummary.run, busy = true), edits = previewEdits, onEdit = {}, onSave = {}, onDelete = {}, review = false, onBack = {})
