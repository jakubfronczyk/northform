package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.ui.NfIcons
import com.jakubfronczyk.northform.ui.NfIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.DashedLine
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import com.jakubfronczyk.northform.ui.ValueCell
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes

/**
 * The start gate (Gate artboard; R24, E1, E13; `RunView.swift:38` was the bare version): the map
 * with a back button and the type chip on top, a card with the heart-rate, GPS and battery rows,
 * the E1 warning, then Start (held until HR ✓ + GPS ✓) with "Start anyway" under it. Both send the
 * same `Start` (option A of engine/01). Every string comes from [GateModel].
 */
@Composable
fun GateScreen(
    snapshot: RunSnapshot,
    route: Route,
    map: @Composable (route: Route) -> Unit,
    start: () -> Unit,
    close: () -> Unit,
    sensor: GateSensor? = null,
    connectSensor: (() -> Unit)? = null,
    /** Opens the Sensor screen, for "Pair a sensor" when nothing is paired; null hides that button. */
    pairSensor: (() -> Unit)? = null,
) {
    val model = GateModel.from(snapshot, sensor)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(290.dp)) {
            map(route)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(Dimens.iconButton).background(Theme.overlay, CircleShape).clip(CircleShape).clickable(onClick = close),
                    contentAlignment = Alignment.Center,
                ) { NfIcon(NfIcons.gateBack, Theme.text, size = 20.dp, label = "Back to Today") }
                Chip(model.typeLabel, Theme.of(snapshot.type))
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .offset(y = -Dimens.sheetOverlap) // the sheet overlaps the map by 22 dp (artboard margin-top: -22px)
                .background(Theme.bg, Shapes.sheet)
                .padding(start = Dimens.gutter, end = Dimens.gutter, top = 20.dp, bottom = Dimens.screenBottom - Dimens.sheetOverlap),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.title, style = Type.gateTitle, color = Theme.text)
                Text(model.subtitle, style = Type.rowLabel, color = Theme.text2)
            }
            ReadinessCard(model, connectSensor, pairSensor)
            model.warning?.let { WarningLine(it) }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BigButton(
                    model.startLabel, start, fill = if (model.startEnabled) Theme.primary else Theme.track,
                    textColor = if (model.startEnabled) Theme.onPrimary else Theme.dim, enabled = model.startEnabled, icon = NfIcons.play,
                )
                if (model.showStartAnyway) {
                    Box(Modifier.fillMaxWidth().height(Dimens.textButton).clickable(onClick = start), contentAlignment = Alignment.Center) {
                        Text("Start anyway", style = Type.bodyStrong, color = Theme.text)
                    }
                }
            }
        }
    }
}

/** The three rows: heart rate · GPS · sensor battery, dashed lines between them. */
@Composable
private fun ReadinessCard(model: GateModel, connectSensor: (() -> Unit)?, pairSensor: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(Theme.card, Shapes.card).padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (val hr = model.hr) {
            is GateModel.HrRow.Ready -> CardRow(icon = { CheckBadge() }, title = "Heart rate", sub = hr.source) {
                BigValue(hr.bpm, "bpm")
            }
            GateModel.HrRow.Waiting -> CardRow(icon = { Spinner() }, title = "Heart rate · waiting…", sub = "No reading in the last 5 s") {}
            is GateModel.HrRow.NoSensor -> CardRow(
                icon = { NfIcon(NfIcons.sensor, Theme.warn) },
                title = "Heart rate", sub = "Sensor not connected", subColor = Theme.warn, minHeight = Dimens.rowTall,
            ) {
                val onClick = when (hr.action) {
                    GateModel.SensorAction.Connect -> connectSensor
                    GateModel.SensorAction.Pair -> pairSensor
                }
                if (onClick != null) {
                    Box(
                        Modifier.height(Dimens.inlineButton).background(Theme.primary, Shapes.inlineButton).clip(Shapes.inlineButton)
                            .clickable(onClick = onClick).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(hr.action.label, style = Type.secondaryBold, color = Theme.onPrimary) }
                }
            }
        }
        DashedLine()
        CardRow(icon = { if (model.gpsReady) CheckBadge() else Spinner() }, title = model.gpsTitle, sub = model.gpsSub, minHeight = 72.dp) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.fixLabel, style = Type.secondaryBold, color = Theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until RunReducer.gateFixes) {
                        Box(Modifier.size(18.dp, 6.dp).background(if (i < model.fixesLit) Theme.ok else Theme.unlit, Shapes.fixSegment))
                    }
                }
            }
        }
        model.battery?.let { battery ->
            DashedLine()
            CardRow(icon = { NfIcon(NfIcons.battery(battery.low), if (battery.low) Theme.warn else Theme.text2) }, title = "Sensor battery", sub = null, minHeight = 60.dp) {
                BigValue(battery.percent.toString(), "%", if (battery.low) Theme.warn else Theme.text)
            }
        }
    }
}

@Composable
private fun CardRow(
    icon: @Composable () -> Unit,
    title: String,
    sub: String?,
    subColor: Color = Theme.text2,
    minHeight: androidx.compose.ui.unit.Dp = Dimens.row,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyStrong, color = Theme.text)
            if (sub != null) Text(sub, style = Type.tertiaryText, color = subColor)
        }
        trailing()
    }
}

@Composable
private fun BigValue(value: String, unit: String, color: Color = Theme.text) = ValueCell(value, unit = unit, color = color, unitGap = 3.dp)

/** The green check disc. */
@Composable
private fun CheckBadge() {
    Box(Modifier.size(32.dp).background(Theme.ok, CircleShape), contentAlignment = Alignment.Center) {
        NfIcon(NfIcons.check, Theme.bg, size = 18.dp)
    }
}

/** A static ring standing in for the artboard's spinner (no animation needed to read the state). */
@Composable
private fun Spinner() {
    Box(Modifier.size(28.dp).border(2.4.dp, Theme.track, CircleShape))
    Box(Modifier.size(28.dp).border(2.4.dp, Theme.spinnerArc, Shapes.spinnerArc))
}

@Composable
private fun WarningLine(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NfIcon(NfIcons.warning, Theme.warn, size = 20.dp)
        Text(text, style = Type.secondaryStrong, color = Theme.warn)
    }
}
