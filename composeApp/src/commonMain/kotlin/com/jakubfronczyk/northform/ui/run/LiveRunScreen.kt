package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.ui.NfIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.Zone
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import com.jakubfronczyk.northform.ui.VDivider
import com.jakubfronczyk.northform.ui.ValueCell
import kotlinx.coroutines.delay
import kotlin.time.Instant
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes

/** The hold-to-stop duration (R27, `LiveRunView.swift:106`). */
internal const val HOLD_TO_STOP_MS = 1500

/**
 * The live run screen (LiveDark artboard, `Features/Run/LiveRunView.swift`): map on top, a 2×2 grid
 * of the big numbers, the zone bar, the small row (split · avg HR · max HR), pause and hold-to-stop.
 * Every value comes from [LiveRunModel]; this function only lays out.
 */
@Composable
fun LiveRunScreen(
    snapshot: RunSnapshot,
    route: Route,
    wallClock: WallClock,
    send: (UserAction) -> Unit,
    map: @Composable (route: Route) -> Unit,
) {
    // Only the paused pill reads the clock every second; the rest recomposes per snapshot.
    val model = remember(snapshot) { LiveRunModel.from(snapshot, wallClock.now()) }
    // One hold for whichever stop control is shown; a phase switch drops it (and cancels the pending Stop).
    var holding by remember(model.paused) { mutableStateOf(false) }
    HoldToStop(holding, send)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            map(route)
            // D41: the map carries no status while all is well; a chip appears only when GPS is lost (HR loss lives in its cell).
            if (model.gpsLost) {
                Box(Modifier.fillMaxWidth().padding(Dimens.gutter).padding(top = Dimens.screenTop - Dimens.gutter), contentAlignment = Alignment.TopEnd) { Chip("GPS lost", Theme.warn) }
            }
            RecenterButton(Modifier.align(Alignment.BottomEnd).padding(end = Dimens.gutter, bottom = Dimens.sheetOverlap + Dimens.gutter))
        }
        Column(
            Modifier.fillMaxWidth()
                .offset(y = -Dimens.sheetOverlap)
                .background(Theme.bg, Shapes.sheet)
                .padding(start = Dimens.gutter, end = Dimens.gutter, top = 18.dp, bottom = Dimens.screenBottom - Dimens.sheetOverlap),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            (snapshot.phase as? RunPhase.Paused)?.let { PausedPill(it.since, wallClock) }
            BigGrid(model)
            ZoneBar(model.litZone)
            SmallRow(model)
            if (model.paused) PausedControls(holding, { holding = it }, send) else RunningControls(holding, { holding = it }, send)
        }
    }
}

/**
 * Shown only after a drag took the map off the route end (U5): one tap puts the camera back to following.
 * Reads the shared [LocalMapCamera]; whatever draws the map reports the drag to the same camera.
 */
@Composable
private fun RecenterButton(modifier: Modifier = Modifier) {
    val camera = LocalMapCamera.current
    if (camera.following) return
    Row(
        modifier.background(Theme.overlay, Shapes.capsule)
            .clip(Shapes.capsule).clickable(onClick = camera::recenter)
            .padding(vertical = 10.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).border(Dimens.stroke, Theme.text, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Re-center", style = Type.statusLabel, color = Theme.text)
    }
}

/** Reads the run's clock once a second while shown, so "Paused · 0:42" counts on the run's own time (D100). */
@Composable
private fun PausedPill(since: Instant, wallClock: WallClock) {
    var now by remember { mutableStateOf(wallClock.now()) }
    LaunchedEffect(wallClock) {
        while (true) {
            now = wallClock.now()
            delay(1000)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Paused · ${RunFormat.time(LiveRunModel.pausedFor(since, now))}", style = Type.pausedPill, color = Theme.bg,
            modifier = Modifier.background(Theme.text, Shapes.pausedPill).padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** Pace · HR / Distance · Time, each a label row, a 54 pt number with its unit, and a 16 dp sub-line. */
@Composable
private fun BigGrid(model: LiveRunModel) {
    val valueColor = if (model.valuesDimmed) Theme.dim else Theme.text
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCell(label = { CellLabel("Pace") }, value = model.pace, unit = "/km", color = if (model.paceDimmed) Theme.dim else Theme.text, sub = "", modifier = Modifier.weight(1f))
            when (val hr = model.hr) {
                LiveRunModel.Hr.Lost -> BigCell(
                    label = { Text("Sensor lost", style = Type.statusLabel, color = Theme.warn) },
                    value = NO_VALUE, unit = null, color = Theme.warn, sub = "reconnecting…", subColor = Theme.text2, modifier = Modifier.weight(1f),
                )
                is LiveRunModel.Hr.Reading -> BigCell(
                    label = {
                        CellLabel("HR")
                        hr.zone?.let { Spacer(Modifier.width(8.dp)); ZonePill(it) }
                    },
                    value = hr.bpm, unit = "bpm", color = valueColor, sub = "", modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCell(label = { CellLabel("Distance") }, value = model.distance, unit = "km", color = valueColor, sub = model.distanceSub, modifier = Modifier.weight(1f))
            BigCell(label = { CellLabel("Time") }, value = model.time, unit = null, color = Theme.text, sub = "", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CellLabel(text: String) = Text(text, style = Type.rowLabel, color = Theme.text2)

@Composable
private fun BigCell(
    label: @Composable RowScope.() -> Unit,
    value: String,
    unit: String?,
    color: Color,
    sub: String,
    modifier: Modifier = Modifier,
    subColor: Color = Theme.warn,
) = ValueCell(
    value, modifier, unit = unit, valueStyle = Type.number54Black, color = color, unitStyle = Type.unitLabel, unitLift = 10.dp, maxLines = 1,
    label = { Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, content = label) },
    footer = { Text(sub, style = Type.tertiaryStrong, color = subColor, modifier = Modifier.height(16.dp)) },
)

/** Six segments, the current zone lit in its colour and taller; none lit while the sensor is lost. */
@Composable
private fun ZoneBar(lit: Int?) {
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (z in 0..5) {
            val on = z == lit
            Box(Modifier.weight(1f).height(if (on) 8.dp else 4.dp).background(if (on) Theme.zoneColors[z] else Theme.unlit, Shapes.bar))
        }
    }
}

/** Split · Avg HR · Max HR — the H1 averages the Swift build never showed live. */
@Composable
private fun SmallRow(model: LiveRunModel) {
    val valueColor = if (model.valuesDimmed) Theme.dim else Theme.text
    // IntrinsicSize.Min: the rules' fillMaxHeight means "as tall as the tallest cell", not "as tall as the screen" — measured
    // before the weighted map box, an unbounded row would take the whole height and squeeze the map to nothing (seen on the 16e).
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(IntrinsicSize.Min)) {
        ValueCell(model.split, Modifier.weight(1f), labelText = "Split", unit = "/km", color = if (model.paceDimmed) Theme.dim else Theme.text, maxLines = 1)
        SmallCellAfterRule("Avg HR", model.hrAvg, valueColor)
        SmallCellAfterRule("Max HR", model.hrMax, valueColor)
    }
}

/** A bpm cell of the small row with the rule before it. */
@Composable
private fun RowScope.SmallCellAfterRule(label: String, value: String, color: Color) {
    Row(Modifier.weight(1f)) {
        VDivider(Modifier.fillMaxHeight())
        ValueCell(value, Modifier.padding(start = 12.dp), labelText = label, unit = "bpm", color = color, maxLines = 1)
    }
}

/** Running: pause circle + "Hold to stop" that fills while held; "Release to cancel" under it (`LiveRunView.swift:87`). */
@Composable
private fun RunningControls(holding: Boolean, onHolding: (Boolean) -> Unit, send: (UserAction) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundControl(border = Theme.ring, onClick = { send(UserAction.Pause) }) { PauseBars() }
            HoldButton(holding, onHolding, modifier = Modifier.weight(1f))
        }
        Text(
            if (holding) "Release to cancel" else "", style = Type.caption, color = Theme.text2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.End,
        )
    }
}

/** Paused: a stop circle that must be held + a white "Resume" button; "Hold the circle to stop" under them. */
@Composable
private fun PausedControls(holding: Boolean, onHolding: (Boolean) -> Unit, send: (UserAction) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundControl(border = Theme.primary, modifier = Modifier.holdGesture(onHolding)) {
                Box(Modifier.size(18.dp).background(Theme.text, Shapes.stopSquare))
            }
            BigButton("Resume", { send(UserAction.Resume) }, Modifier.weight(1f), fill = Theme.text, textColor = Theme.bg, icon = NfIcons.play)
        }
        Text(if (holding) "Keep holding…" else "Hold the circle to stop", style = Type.caption, color = Theme.text2, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Sends Stop once the hold lasted [HOLD_TO_STOP_MS]; releasing earlier cancels (the effect is cancelled with `holding`). */
@Composable
private fun HoldToStop(holding: Boolean, send: (UserAction) -> Unit) {
    LaunchedEffect(holding) {
        if (holding) {
            delay(HOLD_TO_STOP_MS.toLong())
            send(UserAction.Stop)
        }
    }
}

/** Press-and-hold as a modifier: `detectTapGestures.onPress` + `tryAwaitRelease` (Compose's long-press primitive). */
private fun Modifier.holdGesture(onHolding: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = {
        onHolding(true)
        tryAwaitRelease()
        onHolding(false)
    })
}

@Composable
private fun RoundControl(border: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.size(Dimens.bigButton).border(Dimens.stroke, border, CircleShape).let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Two drawn bars: the font's `❚❚` glyphs sit so close they read as one block on the phone. */
@Composable
private fun PauseBars() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(2) { Box(Modifier.size(width = 6.dp, height = 22.dp).background(Theme.text, Shapes.pauseBar)) }
    }
}

/** The stop bar: primary while idle; while held, a dark track with an accent fill growing over the hold time. */
@Composable
private fun HoldButton(holding: Boolean, onHolding: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (holding) 1f else 0f, tween(if (holding) HOLD_TO_STOP_MS else 150), label = "hold")
    Box(
        modifier.height(Dimens.bigButton).clip(Shapes.bigButton).background(if (holding) Theme.track else Theme.primary).holdGesture(onHolding),
        contentAlignment = Alignment.Center,
    ) {
        if (holding) Box(Modifier.fillMaxHeight().fillMaxWidth(fill).align(Alignment.CenterStart).background(Theme.primary))
        Text(if (holding) "Keep holding…" else "Hold to stop", style = Type.sheetTitle, color = Theme.text)
    }
}

/** `LiveRunView.swift:159`. */
@Composable
fun Chip(text: String, color: Color) {
    Row(
        Modifier.background(Theme.overlay, Shapes.capsule).padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Type.statusLabel, color = color)
    }
}

/** `LiveRunView.swift:174` — "Z3 Moderate" in the zone colour; Z0 keeps light text on its grey. */
@Composable
fun ZonePill(zone: Zone) {
    // D23 + D29: the zone value carries its number, label and name; Theme only colours it.
    val color = Theme.zoneColors[zone.number]
    Text(
        "${zone.label} ${zone.name}",
        style = Type.pillLabel,
        color = if (zone.number == 0) Theme.text else Theme.bg,
        modifier = Modifier.background(color, Shapes.capsule).padding(vertical = 4.dp, horizontal = 10.dp),
    )
}
