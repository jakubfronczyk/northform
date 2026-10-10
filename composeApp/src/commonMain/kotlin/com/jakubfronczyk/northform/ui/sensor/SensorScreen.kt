package com.jakubfronczyk.northform.ui.sensor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Mode
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Signal

/**
 * The Sensor screen (SU10, D72), built from `design/canvas/project/Sensor.dc.html` and its
 * disconnected / searching / notFound variants; the no-sensor-paired case borrows the scanning rows
 * of `SetupPair.dc.html`. Dumb on purpose: every state and every rule comes from [SensorScreenModel];
 * this file only lays out `Ui` and forwards taps.
 */
@Composable
fun SensorScreen(model: SensorScreenModel, onClose: () -> Unit) {
    LaunchedEffect(model) { model.follow() } // the Swift `.task {}`: collectors live exactly as long as the screen
    val ui by model.ui.collectAsState()
    SensorScreenContent(ui, model::connect, model::pair, model::cancel, model::disconnect, onClose)
}

/** The screen from values alone, so previews need no model. */
@Composable
fun SensorScreenContent(
    ui: SensorScreenModel.Ui,
    connect: () -> Unit,
    pair: (com.jakubfronczyk.northform.core.SensorId) -> Unit,
    cancel: () -> Unit,
    disconnect: () -> Unit,
    close: () -> Unit,
) {
    val look = Look.of(ui)
    Column(
        Modifier.fillMaxSize().background(Theme.bg).padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(close)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Hero(look)
            when (ui.mode) {
                Mode.Connected -> {
                    ConnectedCard(ui)
                    if (ui.firmwareTooOld) FirmwareWarning(ui.firmware ?: "")
                }
                Mode.Searching -> if (ui.paired != null) SearchingCard(ui.paired.code) else NearbyCard(ui.nearby, pair)
                Mode.Disconnected -> ui.paired?.let { PairedCard(it, ui.firmware) }
                Mode.NotFound -> TipsCard()
                Mode.BluetoothOff, Mode.Unauthorized -> {}
            }
            ui.error?.let { Text(it, style = Type.rowLabel, color = Theme.warn) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            when (ui.mode) {
                Mode.Connected -> { OutlineButton("Disconnect", disconnect, Modifier.widthIn(min = 128.dp)); LightButton("Done", close, Modifier.weight(1f)) }
                Mode.Searching -> OutlineButton("Cancel", cancel, Modifier.weight(1f))
                Mode.Disconnected -> FilledButton(if (ui.paired != null) "Connect" else "Find sensor", connect, Modifier.weight(1f))
                Mode.NotFound -> FilledButton("Try again", connect, Modifier.weight(1f))
                Mode.BluetoothOff, Mode.Unauthorized -> LightButton("Done", close, Modifier.weight(1f))
            }
        }
    }
}

/** Ring colour, status and sub-line per artboard variant (`Sensor.dc.html` → `look`). */
private class Look(val ring: Color, val status: Color, val title: String, val sub: String, val dash: FloatArray?) {
    companion object {
        fun of(ui: SensorScreenModel.Ui): Look {
            val code = ui.paired?.code
            return when (ui.mode) {
                Mode.Connected -> Look(Theme.ok, Theme.ok, "Connected", "Your Verity Sense is ready. Heart rate below is live.", null)
                Mode.Disconnected ->
                    if (ui.paired != null) Look(Theme.warn, Theme.warn, "Not connected", "Turn the sensor on, then connect.", null)
                    else Look(Theme.warn, Theme.warn, "No sensor paired", "Turn on your Polar sensor and keep it close to the phone.", null)
                Mode.Searching -> Look(Theme.text2, Theme.text, "Searching…", "Turn the sensor on: press its button until the light blinks.", floatArrayOf(6f, 10f))
                Mode.NotFound -> Look(Theme.warn, Theme.warn, "Can’t find it", if (code != null) "No signal from $code in 30 s." else "No Polar sensor answered in 30 s.", floatArrayOf(2f, 12f))
                Mode.BluetoothOff -> Look(Theme.warn, Theme.warn, "Bluetooth is off", "Turn Bluetooth on in Control Centre, then come back.", floatArrayOf(2f, 12f))
                Mode.Unauthorized -> Look(Theme.warn, Theme.warn, "Bluetooth not allowed", "Allow Bluetooth for Northform in Settings, then come back.", floatArrayOf(2f, 12f))
            }
        }
    }
}

@Composable
private fun Header(close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clickable(onClick = close), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(22.dp)) {
                val p = Path().apply { moveTo(size.width * 0.62f, size.height * 0.2f); lineTo(size.width * 0.33f, size.height * 0.5f); lineTo(size.width * 0.62f, size.height * 0.8f) }
                drawPath(p, Theme.text, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Sensor", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Theme.text)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(34.dp))
    }
}

@Composable
private fun Hero(look: Look) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(190.dp)) {
                val c = center
                drawCircle(look.ring.copy(alpha = 0.18f), radius = 92.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                drawCircle(
                    look.ring.copy(alpha = 0.35f), radius = 72.dp.toPx(), center = c,
                    style = Stroke(2.dp.toPx(), pathEffect = look.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it[0].dp.toPx(), it[1].dp.toPx())) }),
                )
                drawCircle(Theme.card, radius = 52.dp.toPx(), center = c)
                drawCircle(look.ring, radius = 52.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
                sensorGlyph(look.ring, 46.dp.toPx(), 1.8.dp.toPx())
            }
        }
        Text(look.title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = look.status)
        Text(look.sub, style = Type.secondaryText, color = Theme.text2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
    }
}

/** The armband glyph of the artboard: a rounded rectangle with a heartbeat trace, drawn about [DrawScope.center]. */
private fun DrawScope.sensorGlyph(color: Color, side: Float, stroke: Float) {
    val u = side / 24f
    val left = center.x - 12 * u
    val top = center.y - 12 * u
    val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(color, Offset(left + 6 * u, top + 5 * u), Size(12 * u, 14 * u), CornerRadius(4 * u), style = style)
    val beat = Path().apply {
        moveTo(left + 9 * u, top + 12 * u); lineTo(left + 10.5f * u, top + 12 * u); lineTo(left + 11.5f * u, top + 10 * u)
        lineTo(left + 13 * u, top + 14 * u); lineTo(left + 14 * u, top + 12 * u); lineTo(left + 15 * u, top + 12 * u)
    }
    drawPath(beat, color, style = style)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun ConnectedCard(ui: SensorScreenModel.Ui) = Card {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(ui.paired?.displayName ?: "Polar sensor", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Theme.text)
        Spacer(Modifier.weight(1f))
        Text(ui.paired?.code ?: "", style = Type.tertiaryText, color = Theme.text3)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Live heart rate", style = Type.rowLabel, color = Theme.text2)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ui.bpm?.toString() ?: "–", style = Type.number54Black, color = Theme.text)
                Text(" bpm", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Theme.text2, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Box(Modifier.size(8.dp).background(if (ui.bpm != null) Theme.ok else Theme.text3, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (ui.bpm != null) "updating every second" else "waiting for the first beat", style = Type.tertiaryText, color = if (ui.bpm != null) Theme.ok else Theme.text3)
        }
    }
    DashedLine()
    Row(Modifier.fillMaxWidth()) {
        Stat("Battery", ui.battery?.let { "$it %" } ?: "–", Theme.text, Modifier.weight(1f))
        Divider()
        SignalStat(ui.signal, Modifier.weight(1f).padding(start = 12.dp))
        Divider()
        Stat("Firmware", ui.firmware ?: "–", if (ui.firmwareTooOld) Theme.warn else Theme.text, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.tertiaryText, color = Theme.text2)
        Text(value, style = Type.number22, color = color)
    }
}

/** Four bars, lit by bucket (3 = Good, 2 = Fair, 1 = Weak), "–" when no advert was seen. */
@Composable
private fun SignalStat(signal: Signal?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Signal", style = Type.tertiaryText, color = Theme.text2)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.height(22.dp)) {
            val lit = when (signal) { Signal.Good -> 3; Signal.Fair -> 2; Signal.Weak -> 1; null -> 0 }
            listOf(7, 12, 17, 22).forEachIndexed { i, h ->
                Box(Modifier.width(5.dp).height(h.dp).background(if (i < lit) Theme.text else Color(0xFF707175), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(3.dp))
            }
            Spacer(Modifier.width(3.dp))
            Text(signal?.name ?: "–", style = Type.unitLabel.copy(fontWeight = FontWeight.Bold), color = Theme.text)
        }
    }
}

@Composable
private fun RowScope.Divider() = Box(Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically).background(Theme.line))

@Composable
private fun DashedLine() = Canvas(Modifier.fillMaxWidth().height(1.dp)) {
    drawLine(Theme.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
}

@Composable
private fun FirmwareWarning(version: String) {
    Row(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(22.dp).padding(top = 1.dp)) {
            val tri = Path().apply { moveTo(size.width / 2, size.height * 0.12f); lineTo(size.width * 0.92f, size.height * 0.88f); lineTo(size.width * 0.08f, size.height * 0.88f); close() }
            drawPath(tri, Theme.warn, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            drawLine(Theme.warn, Offset(size.width / 2, size.height * 0.42f), Offset(size.width / 2, size.height * 0.62f), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Theme.warn, 1.2.dp.toPx(), Offset(size.width / 2, size.height * 0.75f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Firmware $version is too old", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Theme.warn)
            Text("Live runs work. Recording without the phone needs 2.1.0. Update in Polar Flow.", style = Type.rowLabel, color = Theme.text2)
        }
    }
}

@Composable
private fun SearchingCard(code: String) = Card {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Looking for $code", style = Type.secondaryText.copy(fontWeight = FontWeight.Bold), color = Theme.text)
        Text("Usually takes a few seconds. Keep the sensor close to the phone.", style = Type.rowLabel, color = Theme.text2)
    }
}

/** `SetupPair.dc.html` scanning rows: every Polar advert, a Pair button each; nothing connects by itself. */
@Composable
private fun NearbyCard(nearby: List<DiscoveredSensor>, pair: (com.jakubfronczyk.northform.core.SensorId) -> Unit) = Card {
    Column {
        Text("Scanning…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.text, modifier = Modifier.padding(bottom = 12.dp))
        if (nearby.isEmpty()) Text("Nothing yet. Press the sensor button until its light blinks.", style = Type.rowLabel, color = Theme.text2)
        for (d in nearby) {
            val info = PairedSensorInfo(d.id, d.name)
            DashedLine()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(Theme.track, CircleShape), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(20.dp)) { sensorGlyph(Theme.text2, 20.dp.toPx(), 1.6.dp.toPx()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(info.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Theme.text)
                    Text("${info.code} · ${d.rssi} dBm", style = Type.tertiaryText, color = Theme.text2)
                }
                Box(
                    Modifier.height(44.dp).background(Theme.primary, RoundedCornerShape(12.dp)).clickable { pair(d.id) }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Pair", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            }
        }
    }
}

@Composable
private fun PairedCard(paired: PairedSensorInfo, firmware: String?) = Card {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).background(Theme.bg, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(20.dp)) { sensorGlyph(Theme.text2, 20.dp.toPx(), 1.6.dp.toPx()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(paired.displayName, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Theme.text)
            Text(listOfNotNull("Paired", paired.code, firmware?.let { "firmware $it" }).joinToString(" · "), style = Type.tertiaryText, color = Theme.text2)
        }
    }
}

/** SU10: "Tips when not found: press button, wear it, charge it." */
@Composable
private fun TipsCard() = Card {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Try this", style = Type.secondaryText.copy(fontWeight = FontWeight.Bold), color = Theme.text)
        listOf(
            "Press the sensor button. A blinking light means it is on.",
            "Wear it on your arm. Off the skin it can go to sleep.",
            "Still nothing? Charge it for a few minutes.",
        ).forEachIndexed { i, tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).background(Theme.line, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Type.pillLabel, color = Theme.text)
                }
                Text(tip, style = Type.secondaryText, color = Theme.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilledButton(title: String, action: () -> Unit, modifier: Modifier) =
    ButtonFrame(modifier.background(Theme.primary, RoundedCornerShape(16.dp)), action) { Text(title, style = Type.sheetTitle, color = Color.White) }

@Composable
private fun LightButton(title: String, action: () -> Unit, modifier: Modifier) =
    ButtonFrame(modifier.background(Theme.text, RoundedCornerShape(16.dp)), action) { Text(title, style = Type.sheetTitle, color = Theme.bg) }

@Composable
private fun OutlineButton(title: String, action: () -> Unit, modifier: Modifier) =
    ButtonFrame(modifier.border(1.5.dp, Theme.ring, RoundedCornerShape(16.dp)), action) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Theme.text) }

@Composable
private fun ButtonFrame(modifier: Modifier, action: () -> Unit, label: @Composable () -> Unit) {
    Box(modifier.height(68.dp).clickable(onClick = action).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) { label() }
}
