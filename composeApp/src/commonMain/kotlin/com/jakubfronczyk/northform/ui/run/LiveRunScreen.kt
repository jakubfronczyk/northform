package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import kotlinx.coroutines.delay

/** The live run screen (`Features/Run/LiveRunView.swift`, LiveDark): map on top, numbers in a sheet, pause and hold-to-stop. */
@Composable
fun LiveRunScreen(
    snapshot: RunSnapshot,
    route: Route,
    send: (UserAction) -> Unit,
    map: @Composable (route: Route) -> Unit,
) {
    val paused = snapshot.phase is RunPhase.Paused
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            map(route)
            Row(Modifier.padding(16.dp).padding(top = 54.dp)) {
                Chip("Run", Theme.accent)
                Spacer(Modifier.width(8.dp))
                Chip(if (snapshot.gpsLost) "GPS lost" else "GPS", if (snapshot.gpsLost) Theme.warn else Theme.ok)
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .background(Theme.bg, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .padding(16.dp).padding(bottom = 34.dp),
        ) {
            if (paused) Text("Paused", style = Type.statusLabel, color = Theme.warn)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column {
                    Text("Pace", style = Type.rowLabel, color = Theme.text2)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(RunFormat.pace(snapshot.currentPace), style = Type.number54Black, color = if (paused) Theme.dim else Theme.text)
                        Spacer(Modifier.width(4.dp))
                        Text("/km", style = Type.unitLabel, color = Theme.text2, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Text("split ${RunFormat.pace(snapshot.splitPace)}", style = Type.tertiaryText, color = Theme.text3)
                }
                Spacer(Modifier.weight(1f))
                Heart(snapshot)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Theme.line)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Distance", RunFormat.distance(snapshot.distance), "km", dimmed = paused)
                Spacer(Modifier.weight(1f))
                Stat("Time", RunFormat.time(snapshot.totalTime))
            }
            Spacer(Modifier.height(14.dp))
            Controls(paused, send)
        }
    }
}

/** `LiveRunView.swift:70` — nil while heart rate is lost: never a stale value. */
@Composable
private fun Heart(snapshot: RunSnapshot) {
    Column(horizontalAlignment = Alignment.End) {
        if (snapshot.hrLost) {
            Text("Sensor lost", style = Type.statusLabel, color = Theme.warn)
            Text("–", style = Type.number40, color = Theme.warn)
            Text("reconnecting…", style = Type.tertiaryText, color = Theme.text3)
        } else {
            snapshot.zone?.let { ZonePill(it) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(snapshot.hrNow?.toString() ?: "–", style = Type.number40, color = Theme.text)
                Spacer(Modifier.width(4.dp))
                Text("bpm", style = Type.unitLabel, color = Theme.text2, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** `LiveRunView.swift:87` — pause/resume, and hold-to-stop for 1.5 s (R27). */
@Composable
private fun Controls(paused: Boolean, send: (UserAction) -> Unit) {
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (holding) {
            delay(1500)
            send(UserAction.Stop)
            holding = false
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(68.dp)
                .background(if (paused) Theme.text else Color.Transparent, CircleShape)
                .border(1.5.dp, Theme.ring, CircleShape)
                .clickable { send(if (paused) UserAction.Resume else UserAction.Pause) },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (paused) "▶" else "❚❚", style = Type.number22, color = if (paused) Theme.bg else Theme.text)
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.weight(1f).height(68.dp)
                .background(if (holding) Theme.accent else Theme.primary, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (holding) "Keep holding…" else "Hold to stop", style = Type.sheetTitle, color = Theme.text)
        }
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

/** `LiveRunView.swift:174`. */
@Composable
fun ZonePill(zone: Int) {
    val info = Theme.zones[zone.coerceIn(0, 5)]
    Text(
        "Z$zone ${info.name}",
        style = Type.pillLabel,
        color = if (zone == 0) Theme.text else Theme.bg,
        modifier = Modifier.background(info.color, RoundedCornerShape(50)).padding(vertical = 4.dp, horizontal = 10.dp),
    )
}

/** `LiveRunView.swift:188`. */
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
