package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlin.time.Instant

/**
 * First-launch Setup (first-launch-setup spec, D71): About you → Two permissions → Pair your sensor → Your zones, then
 * Today. The steps are local state; the profile is saved on step 1 ([onSaveProfile]), so steps 2–4 can be left at any
 * time and done later from the gate, the Sensor sheet and Settings. [finish] closes Setup.
 */
@Composable
fun SetupScreen(
    profile: Profile?,
    now: Instant,
    tzOffset: Int,
    onSaveProfile: (Profile) -> Unit,
    permissions: SetupPermissions,
    allowLocation: () -> Unit,
    openSettings: () -> Unit,
    sensor: SensorScreenModel.Ui,
    search: () -> Unit,
    pair: (SensorId) -> Unit,
    finish: () -> Unit,
) {
    var step by remember { mutableStateOf(SetupStep.AboutYou) }
    var editingZones by remember { mutableStateOf(false) }
    val back: (() -> Unit)? = step.back()?.let { previous -> { step = previous } }
    fun next() { step.next()?.let { step = it } ?: finish() }
    when {
        editingZones && profile != null -> ZoneEditorScreen(profile, onSave = { onSaveProfile(it); editingZones = false }, back = { editingZones = false })
        step == SetupStep.AboutYou -> SetupAboutYouScreen(
            initial = profile?.let(ProfileForm::of) ?: ProfileForm.blank, zones = profile?.zones ?: HRZones.standard, now = now, tzOffset = tzOffset,
            onContinue = { onSaveProfile(it); next() },
        ) { Progress(step, back = null) }
        step == SetupStep.Permissions -> SetupPage(step, back, "Two permissions", "Only what a run needs. Nothing else is asked now.", "Continue", ::next) {
            Permissions(permissions, allowLocation, openSettings)
        }
        step == SetupStep.Pair -> SetupPage(
            step, back, "Pair your sensor", "Turn on the Verity Sense and keep it close to the phone.",
            if (sensor.paired != null) "Continue" else "Skip, pair later", ::next,
        ) {
            LaunchedEffect(Unit) { if (sensor.paired == null) search() }
            Pair(sensor, pair)
        }
        // Step 1 saved the profile before this step is reached; null only for the moment the save takes to land.
        profile != null -> SetupZones.of(profile).let { zones ->
            SetupPage(step, back, "Your zones", zones.subtitle, "Start using the app", ::next) { Zones(zones) { editingZones = true } }
        }
    }
}

/** One Setup page: the progress, a title and subtitle, the step's content and its button at the bottom. */
@Composable
private fun SetupPage(step: SetupStep, back: (() -> Unit)?, title: String, subtitle: String, button: String, onButton: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PinnedScreen(button = { BigButton(button, onButton) }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Progress(step, back)
            Text(title, style = Type.pageTitle, color = Theme.text)
            Text(subtitle, style = Type.secondaryText, color = Theme.text2)
        }
        content()
    }
}

/** "‹  ▬ ▬ ▬ ▬  2 of 4": Back (none on step 1), four segments lit up to this step, and the count. */
@Composable
private fun Progress(step: SetupStep, back: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (back != null) {
            Box(Modifier.offset(x = (-10).dp).size(Dimens.iconButton).clickable(onClick = back), contentAlignment = Alignment.Center) {
                NfIcon(NfIcons.back, Theme.text, size = 22.dp, label = "Back")
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SetupStep.entries.forEach { s ->
                Box(Modifier.weight(1f).height(4.dp).clip(Shapes.bar).background(if (s.ordinal <= step.ordinal) Theme.accent else Theme.track))
            }
        }
        Text(step.progress, style = Type.caption, color = Theme.text2)
    }
}

@Composable
private fun Permissions(model: SetupPermissions, allowLocation: () -> Unit, openSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(horizontal = Dimens.gutter)) {
        PermissionRow("Bluetooth", "To connect the armband.", model.bluetooth, allow = {}, openSettings)
        DashedLine()
        PermissionRow("Location", "For map, distance and pace. While using the app, and in the background during a run.", model.location, allowLocation, openSettings)
    }
    model.locationNote?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
    Text("Pick \"While Using the App\" when iOS asks. The app never asks for \"Always\".", style = Type.caption, color = Theme.text3)
}

@Composable
private fun PermissionRow(title: String, why: String, row: SetupPermissions.Row, allow: () -> Unit, openSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.row).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyStrong, color = Theme.text)
            Text(why, style = Type.tertiaryText, color = Theme.text2)
            Text(row.status, style = Type.tertiaryStrong, color = if (row.allowed) Theme.ok else Theme.warn)
        }
        when (row.action) {
            SetupPermissions.Action.Allow -> SmallButton("Allow", allow)
            SetupPermissions.Action.OpenSettings -> SmallButton("Open Settings", openSettings)
            null -> Unit
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(Dimens.inlineButton).clip(Shapes.button).border(Dimens.stroke, Theme.outline, Shapes.button).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.secondaryStrong, color = Theme.text) }
}

/** The pairing list over the Sensor screen's model (E16): searching, nearby sensors with Pair, the paired one's firmware and battery. */
@Composable
private fun Pair(ui: SensorScreenModel.Ui, pair: (SensorId) -> Unit) {
    val paired = ui.paired
    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(Dimens.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            paired != null -> {
                SensorRow(paired, trailing = "Paired")
                DashedLine()
                FactRow("Firmware", "2.1.0 or newer needed", ui.firmware ?: "—", warn = ui.firmwareTooOld)
                FactRow("Battery", "read from the sensor", ui.battery?.let { "$it %" } ?: "—", warn = false)
            }
            ui.mode == SensorScreenModel.Mode.BluetoothOff -> Text("Bluetooth is off. Turn it on in Control Center, or skip and pair later.", style = Type.secondaryText, color = Theme.warn)
            ui.mode == SensorScreenModel.Mode.Unauthorized -> Text("Bluetooth isn't allowed for this app. Allow it in iOS Settings, or skip and pair later.", style = Type.secondaryText, color = Theme.warn)
            ui.nearby.isEmpty() -> Text("Scanning…", style = Type.secondaryText, color = Theme.text2)
            else -> ui.nearby.forEachIndexed { i, s ->
                if (i > 0) DashedLine()
                val info = PairedSensorInfo(s.id, s.name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SensorRow(info, trailing = null) }
                    SmallButton("Pair") { pair(s.id) }
                }
            }
        }
    }
    if (ui.firmwareTooOld && paired != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Firmware ${ui.firmware ?: ""} is too old", style = Type.bodyStrong, color = Theme.warn)
            Text("Live runs work. Recording without the phone needs 2.1.0. Update in Polar Flow.", style = Type.tertiaryText, color = Theme.text2)
        }
    }
    ui.error?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
}

@Composable
private fun SensorRow(s: PairedSensorInfo, trailing: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(Dimens.iconButton).background(Theme.bg, CircleShape), contentAlignment = Alignment.Center) { NfIcon(NfIcons.sensor, Theme.text, size = 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.displayName, style = Type.cardTitle, color = Theme.text)
            Text(s.code, style = Type.tertiaryText, color = Theme.text2)
        }
        trailing?.let { Text(it, style = Type.tertiaryStrong, color = Theme.ok) }
    }
}

@Composable
private fun FactRow(label: String, note: String, value: String, warn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = Type.secondaryText, color = Theme.text)
            Text(note, style = Type.caption, color = Theme.text3)
        }
        Text(value, style = Type.secondaryStrong, color = if (warn) Theme.warn else Theme.text)
    }
}

/** The zones table: zone, % of reserve, bpm; Edit boundaries opens the zone editor. */
@Composable
private fun Zones(model: SetupZones, edit: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(horizontal = Dimens.gutter, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("ZONE", style = Type.caption, color = Theme.text3, modifier = Modifier.weight(1.4f))
            Text("% HRR", style = Type.caption, color = Theme.text3, modifier = Modifier.weight(1f))
            Text("BPM", style = Type.caption, color = Theme.text3, modifier = Modifier.weight(1f))
        }
        model.rows.forEachIndexed { zone, row ->
            DashedLine()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(Theme.zoneColors[zone], CircleShape))
                    Text(row.label, style = Type.secondaryStrong, color = Theme.text)
                }
                Text(row.percent, style = Type.secondaryText, color = Theme.text2, modifier = Modifier.weight(1f))
                Text(row.bpm, style = Type.secondaryText, color = Theme.text, modifier = Modifier.weight(1f))
            }
        }
    }
    Text("Edit boundaries", style = Type.secondaryStrong, color = Theme.accent, modifier = Modifier.clickable(onClick = edit).padding(vertical = 8.dp))
    Text("Boundaries are kept as %. If your max changes, the bpm moves with it.", style = Type.caption, color = Theme.text3)
}
