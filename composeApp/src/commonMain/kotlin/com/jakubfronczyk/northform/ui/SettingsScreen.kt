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
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type

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
        // Fits the 16e without scrolling (Jakub's rule): no group labels where a card already names itself, the actions
        // inline on their rows. Scrolling stays only as the fallback for a smaller phone or larger text.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop, bottom = Dimens.screenBottom),
            verticalArrangement = Arrangement.spacedBy(Dimens.blockGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackButton("Back to Today", back)
                Text("Settings", style = Type.pageTitle, color = Theme.text)
            }
            Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(horizontal = Dimens.gutter)) {
                ValueRow("Profile", model.profileValue, model.profileNote, editProfile)
                DashedLine()
                ValueRow("Heart rate", model.heartRate, note = null, editProfile)
            }
            model.profileError?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
            Column(
                Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).clickable(onClick = editZones).padding(Dimens.gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Heart rate zones", style = Type.bodyStrong, color = Theme.text)
                        Text(SettingsModel.ZONES_SUB, style = Type.tertiaryText, color = Theme.text2)
                    }
                    NfIcon(NfIcons.forward, Theme.text2, size = Dimens.iconSmall)
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
            val sensor = model.sensor
            Row(
                Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(Dimens.gutter),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(Dimens.sensorDisc).background(Theme.bg, CircleShape), contentAlignment = Alignment.Center) {
                    NfIcon(NfIcons.sensor, if (sensor == null) Theme.text2 else Theme.text, size = Dimens.icon)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sensor?.name ?: "No sensor paired", style = Type.bodyStrong, color = if (sensor == null) Theme.text2 else Theme.text)
                    sensor?.let { Text(listOfNotNull(it.detail, it.battery).joinToString(" · "), style = Type.tertiaryText, color = Theme.text2) }
                }
                if (sensor != null) InlineAction("Forget", Theme.danger) { confirmForget = true }
            }
            Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Backup", style = Type.bodyStrong, color = Theme.text)
                        Text(model.backupDetail, style = Type.tertiaryText, color = Theme.text2)
                    }
                    InlineAction(model.export.label, Theme.text, enabled = model.export.enabled, onClick = export)
                }
                model.export.line?.let { Text(it, style = Type.caption, color = Theme.text3) }
            }
            Row(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).heightIn(min = Dimens.field).padding(horizontal = Dimens.gutter), verticalAlignment = Alignment.CenterVertically) {
                Text("Units", style = Type.secondaryText, color = Theme.text, modifier = Modifier.weight(1f))
                Text(model.units, style = Type.secondaryText, color = Theme.text2)
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

/** A compact outlined action on its card's row (Forget, Export backup): one line, so the screen fits without scrolling. */
@Composable
private fun InlineAction(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(Dimens.inlineButton).clip(Shapes.inlineButton).border(Dimens.stroke, Theme.outline, Shapes.inlineButton)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.secondaryStrong, color = if (enabled) color else Theme.dim, maxLines = 1) }
}
