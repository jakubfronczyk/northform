package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.NfIcon
import com.jakubfronczyk.northform.ui.NfIcons
import com.jakubfronczyk.northform.ui.ValueCell
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * The cool-down (`Cooldown.dc.html`, run-flow-artboards; `RunView.swift:70` was the bare text). Reads the run's clock
 * ten times a second and folds what it sees into a [CooldownTrace] that lives as long as this screen; the layout is
 * [CooldownContent]. [skip] sends the existing `SkipCooldown`.
 */
@Composable
fun CooldownScreen(snapshot: RunSnapshot, wallClock: WallClock, skip: () -> Unit) {
    val latest by rememberUpdatedState(snapshot)
    var now by remember { mutableStateOf(wallClock.now()) }
    var trace by remember { mutableStateOf(CooldownTrace().add(snapshot, now)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = wallClock.now()
            trace = trace.add(latest, now)
            delay(100)
        }
    }
    CooldownContent(CooldownModel.from(snapshot, now, trace), skip)
}

/**
 * The artboard's layout over a [CooldownModel]: the "Run stopped" chip and the frozen totals, the title, the 60 s ring
 * with the time left, the card with Now · Started at · Drop so far over the chart and its caption (or the sensor-lost
 * line), and Skip at the foot. Everything fits the 16e; nothing scrolls.
 */
@Composable
fun CooldownContent(model: CooldownModel, skip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop, bottom = Dimens.screenBottom)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Chip(model.chipLabel, model.chipColor, background = Theme.card)
            Text(model.totals, style = Type.rowLabel, color = Theme.text2)
        }
        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.title, style = Type.heroTitle, color = Theme.text)
            Text(model.subtitle, style = Type.lead, color = Theme.text2)
        }
        Box(Modifier.padding(top = 34.dp).size(Dimens.cooldownRingBox).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            ProgressRing(model.ring, Dimens.cooldownRing, Dimens.cooldownRingStroke)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.left, style = Type.cooldownLeft, color = Theme.text)
                Text("left", style = Type.liveUnit, color = Theme.text2)
            }
        }
        RecoveryCard(model, Modifier.padding(top = 34.dp))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.align(Alignment.CenterHorizontally).height(Dimens.secondaryButton).clickable(onClick = skip).padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Skip", style = Type.textAction, color = Theme.text2) }
    }
}

/** Now · Started at · Drop so far, dashed rules between them, over the since-stop chart and its caption. */
@Composable
private fun RecoveryCard(model: CooldownModel, modifier: Modifier) {
    val nowColor = if (model.lost) Theme.warn else Theme.text
    Column(modifier.fillMaxWidth().background(Theme.card, Shapes.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            ValueCell(
                model.hrNow, Modifier.weight(1f), unit = "bpm", valueStyle = Type.cooldownNow, color = nowColor, unitStyle = Type.smallUnit, unitLift = 0.dp,
                label = { Text("Now", style = Type.tertiaryText, color = if (model.lost) Theme.warn else Theme.text2) },
            )
            DashedRule()
            ValueCell(model.startedAt, Modifier.weight(1f).padding(start = 12.dp), labelText = "Started at", unit = "bpm", unitStyle = Type.smallUnit, unitLift = 0.dp, spacing = 12.dp)
            DashedRule()
            ValueCell(model.drop, Modifier.weight(1f).padding(start = 12.dp), labelText = "Drop so far", unit = "bpm", unitStyle = Type.smallUnit, unitLift = 0.dp, spacing = 12.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RecoveryChart(model.chart, lost = model.lost)
            model.caption?.let { Text(it, style = Type.caption, color = Theme.text3) }
            model.lostLine?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NfIcon(NfIcons.interrupted, Theme.warn, size = Dimens.iconMini)
                    Text(it, style = Type.tertiaryStrong, color = Theme.warn)
                }
            }
        }
    }
}

/** The 1 dp dashed vertical rule (3 on, 3 off) before a cell (`border-left: 1px dashed`). */
@Composable
private fun DashedRule() = Canvas(Modifier.width(1.dp).fillMaxHeight()) {
    drawLine(Theme.line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
}

/**
 * The heart rate since the stop (`Cooldown.dc.html`'s svg): a dashed line at the start's level, the trace below it
 * (broken at a gap), a dot on the last reading, and a dotted line from there to the minute's end — grey while
 * measuring, warn once the sensor is lost.
 */
@Composable
private fun RecoveryChart(points: List<CooldownModel.ChartPoint?>, lost: Boolean) {
    val accent = if (lost) Theme.warn else Theme.text
    val tail = if (lost) Theme.warn else Theme.line
    Canvas(Modifier.fillMaxWidth().height(Dimens.recoveryChart)) {
        val pad = 4.dp.toPx()
        fun at(p: CooldownModel.ChartPoint) = Offset(p.x * size.width, pad + p.y * (size.height - 2 * pad))
        drawLine(Theme.line, Offset(0f, pad), Offset(size.width, pad), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
        val path = Path()
        var drawing = false
        for (p in points) {
            if (p == null) { drawing = false; continue }
            val o = at(p)
            if (drawing) path.lineTo(o.x, o.y) else path.moveTo(o.x, o.y)
            drawing = true
        }
        drawPath(path, Theme.text, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.lastOrNull { it != null } ?: return@Canvas
        val end = at(last)
        drawLine(tail, end, Offset(size.width, size.height - pad), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx())))
        drawCircle(accent, radius = 4.dp.toPx(), center = end)
    }
}
