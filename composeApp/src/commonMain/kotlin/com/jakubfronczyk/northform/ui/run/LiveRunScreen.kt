package com.jakubfronczyk.northform.ui.run

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.metrics.Zone
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import kotlinx.coroutines.delay

/** Design tokens the LiveDark/Gate artboards use beyond `Theme` (map placeholder, unlit zone segment). */
internal object RunPalette {
    val mapPanel = Color(0xFF1D1F21)
    val unlit = Color(0xFF3A3B3E)
    val held = Color(0xFF2E2F32)
}

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
    val now = rememberNow(wallClock)
    val model = LiveRunModel.from(snapshot, now)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            map(route)
            Row(Modifier.fillMaxWidth().padding(16.dp).padding(top = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Chip("Run", Theme.accent)
                Chip(if (model.gpsLost) "GPS lost" else "GPS", if (model.gpsLost) Theme.warn else Theme.ok)
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .offset(y = (-22).dp)
                .background(Theme.bg, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 50.dp - 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (model.paused) PausedPill(model.pausedFor?.let(RunFormat::time) ?: "")
            BigGrid(model)
            ZoneBar(model.litZone)
            SmallRow(model)
            if (model.paused) PausedControls(send) else RunningControls(send)
        }
    }
}

/** Reads the run's clock once a second while shown, so "Paused · 0:42" counts on the run's own time (D100). */
@Composable
private fun rememberNow(wallClock: WallClock): kotlin.time.Instant {
    var now by remember { mutableStateOf(wallClock.now()) }
    LaunchedEffect(wallClock) {
        while (true) {
            now = wallClock.now()
            delay(1000)
        }
    }
    return now
}

@Composable
private fun PausedPill(time: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Paused · $time", style = RunType.pausedPill, color = Theme.bg,
            modifier = Modifier.background(Theme.text, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
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
                    label = { Text("Sensor lost", style = Type.rowLabel.copy(fontWeight = FontWeight.SemiBold), color = Theme.warn) },
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
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, content = label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.number54Black, color = color, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = Type.unitLabel, color = Theme.text2, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Text(sub, style = Type.tertiaryText.copy(fontWeight = FontWeight.SemiBold), color = subColor, modifier = Modifier.height(16.dp))
    }
}

/** Six segments, the current zone lit in its colour and taller; none lit while the sensor is lost. */
@Composable
private fun ZoneBar(lit: Int?) {
    Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        for (z in 0..5) {
            val on = z == lit
            Box(Modifier.weight(1f).height(if (on) 8.dp else 4.dp).background(if (on) Theme.zones[z].color else RunPalette.unlit, RoundedCornerShape(4.dp)))
        }
    }
}

/** Split · Avg HR · Max HR — the H1 averages the Swift build never showed live. */
@Composable
private fun SmallRow(model: LiveRunModel) {
    val valueColor = if (model.valuesDimmed) Theme.dim else Theme.text
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SmallCell("Split", model.split, "/km", if (model.paceDimmed) Theme.dim else Theme.text, first = true, modifier = Modifier.weight(1f))
        SmallCell("Avg HR", model.hrAvg, "bpm", valueColor, modifier = Modifier.weight(1f))
        SmallCell("Max HR", model.hrMax, "bpm", valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SmallCell(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier, first: Boolean = false) {
    Row(modifier) {
        if (!first) Box(Modifier.width(1.dp).fillMaxHeight().background(Theme.line))
        Column(Modifier.padding(start = if (first) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = Type.tertiaryText, color = Theme.text2)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = RunType.number24, color = color, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = Type.tertiaryText, color = Theme.text2, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

/** Running: pause circle + "Hold to stop" that fills while held; "Release to cancel" under it (`LiveRunView.swift:87`). */
@Composable
private fun RunningControls(send: (UserAction) -> Unit) {
    var holding by remember { mutableStateOf(false) }
    HoldToStop(holding, send)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundControl(border = Theme.ring, onClick = { send(UserAction.Pause) }) {
                Text("❚❚", style = Type.number22, color = Theme.text)
            }
            HoldButton(holding, onHolding = { holding = it }, modifier = Modifier.weight(1f))
        }
        Text(
            if (holding) "Release to cancel" else "", style = RunType.caption, color = Theme.text2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.End,
        )
    }
}

/** Paused: a stop circle that must be held + a white "Resume" button; "Hold the circle to stop" under them. */
@Composable
private fun PausedControls(send: (UserAction) -> Unit) {
    var holding by remember { mutableStateOf(false) }
    HoldToStop(holding, send)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundControl(border = Theme.primary, modifier = Modifier.holdGesture { holding = it }) {
                Box(Modifier.size(18.dp).background(Theme.text, RoundedCornerShape(4.dp)))
            }
            Box(
                Modifier.weight(1f).height(68.dp).background(Theme.text, RoundedCornerShape(16.dp)).clickable { send(UserAction.Resume) },
                contentAlignment = Alignment.Center,
            ) {
                Text("▶  Resume", style = Type.sheetTitle, color = Theme.bg)
            }
        }
        Text(if (holding) "Keep holding…" else "Hold the circle to stop", style = RunType.caption, color = Theme.text2, modifier = Modifier.padding(top = 8.dp))
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
        modifier.size(68.dp).border(1.5.dp, border, CircleShape).let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The stop bar: primary while idle; while held, a dark track with an accent fill growing over the hold time. */
@Composable
private fun HoldButton(holding: Boolean, onHolding: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(if (holding) 1f else 0f, tween(if (holding) HOLD_TO_STOP_MS else 150), label = "hold")
    Box(
        modifier.height(68.dp).clip(RoundedCornerShape(16.dp)).background(if (holding) RunPalette.held else Theme.primary).holdGesture(onHolding),
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
        Modifier.background(Theme.bg.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(vertical = 8.dp, horizontal = 12.dp),
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
    // D23: the zone value carries its number (colour) and label; the canvas names stay in Theme until the screens settle the words.
    val info = Theme.zones[zone.number]
    Text(
        "${zone.label} ${info.name}",
        style = Type.pillLabel,
        color = if (zone.number == 0) Theme.text else Theme.bg,
        modifier = Modifier.background(info.color, RoundedCornerShape(50)).padding(vertical = 4.dp, horizontal = 10.dp),
    )
}

/** Text styles the run artboards use beyond `Type` (kept in this lane's package; tabular digits like `Type.number`). */
internal object RunType {
    val number24 = Type.number(24)
    val pausedPill = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val caption = TextStyle(fontSize = 12.sp)
}

/** `LiveRunView.swift:188` — still used by the cool-down card. */
@Composable
fun Stat(label: String, value: String, unit: String? = null, dimmed: Boolean = false) {
    Column {
        Text(label, style = Type.rowLabel, color = Theme.text2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.number40, color = if (dimmed) Theme.dim else Theme.text)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = Type.unitLabel, color = Theme.text2, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
