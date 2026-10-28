package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Dimens

/**
 * The Settings artboard (`Settings.dc.html`, profile-settings spec): the profile and heart rates, the zones bar, the
 * paired sensor with Forget, units, Export backup and the version. Lays out a [SettingsModel]; decides nothing.
 */
@Composable
fun SettingsScreen(
    model: SettingsModel,
    back: () -> Unit,
    editProfile: () -> Unit,
    editZones: () -> Unit,
    export: () -> Unit,
    forgetSensor: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop, bottom = Dimens.screenBottom),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.offset(x = (-10).dp).size(Dimens.iconButton).clickable(onClick = back), contentAlignment = Alignment.Center) {
                    NfIcon(NfIcons.back, Theme.text, size = 22.dp, label = "Back to Today")
                }
                Text("Settings", style = Type.pageTitle, color = Theme.text)
            }
            Section("Profile") {
                Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(horizontal = 16.dp)) {
                    ValueRow("Profile", model.profileValue, model.profileNote, editProfile)
                    DashedLine()
                    ValueRow("Heart rate", model.heartRate, note = null, editProfile)
                }
                model.profileError?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
            }
            Section("Heart rate zones") {
                Column(
                    Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).clickable(onClick = editZones).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Zones", style = Type.bodyStrong, color = Theme.text)
                            Text(SettingsModel.ZONES_SUB, style = Type.tertiaryText, color = Theme.text2)
                        }
                        NfIcon(NfIcons.forward, Theme.text2, size = 18.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Theme.zoneColors.forEachIndexed { zone, color ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().height(8.dp).background(color, Shapes.bar))
                                Text("Z$zone", style = Type.caption, color = Theme.text3)
                            }
                        }
                    }
                }
            }
            Section("Sensor") {
                val sensor = model.sensor
                Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (sensor == null) {
                        Text("No sensor paired", style = Type.secondaryText, color = Theme.text2)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(Dimens.sensorDisc).background(Theme.bg, CircleShape), contentAlignment = Alignment.Center) {
                                NfIcon(NfIcons.sensor, Theme.text, size = 22.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sensor.name, style = Type.cardTitle, color = Theme.text)
                                Text(sensor.detail, style = Type.tertiaryText, color = Theme.text2)
                            }
                            sensor.battery?.let { Text(it, style = Type.secondaryStrong, color = Theme.text) }
                        }
                        OutlinedButton("Forget sensor", Theme.danger) { confirmForget = true }
                    }
                }
            }
            Section("Units") {
                Row(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Units", style = Type.secondaryText, color = Theme.text, modifier = Modifier.weight(1f))
                    Text(model.units, style = Type.secondaryText, color = Theme.text2)
                }
            }
            Section("Data") {
                Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Backup", style = Type.cardTitle, color = Theme.text)
                        Text(model.backupDetail, style = Type.tertiaryText, color = Theme.text2)
                    }
                    OutlinedButton(model.export.label, Theme.text, enabled = model.export.enabled, onClick = export)
                    model.export.line?.let { Text(it, style = Type.caption, color = Theme.text3) }
                }
            }
            Text(model.version, style = Type.caption, color = Theme.text3, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        if (confirmForget) {
            ConfirmSheet(
                "Forget this sensor?", "The app stops connecting to ${model.sensor?.name ?: "it"}. Pair it again from the Sensor screen.",
                confirm = "Forget sensor", keep = "Keep sensor",
                onKeep = { confirmForget = false }, onConfirm = { confirmForget = false; forgetSensor() },
            )
        }
    }
}

/** A titled group: the grey label above its card. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = Type.tertiaryText, color = Theme.text2)
    content()
}

/** "Profile · 30 · 78 kg · 182 cm / born 14.03.1996 · male ›": label left, value and note right. */
@Composable
private fun ValueRow(label: String, value: String, note: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = Type.secondaryText, color = Theme.text, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(value, style = Type.secondaryText, color = Theme.text)
            note?.let { Text(it, style = Type.caption, color = Theme.text3) }
        }
        NfIcon(NfIcons.forward, Theme.text2, size = 16.dp)
    }
}

/** The artboard's 48 dp outlined button (Forget sensor, Export backup). */
@Composable
private fun OutlinedButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(Dimens.secondaryButton).clip(Shapes.button).border(Dimens.stroke, Theme.outline, Shapes.button).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.secondaryStrong, color = if (enabled) color else Theme.dim) }
}
