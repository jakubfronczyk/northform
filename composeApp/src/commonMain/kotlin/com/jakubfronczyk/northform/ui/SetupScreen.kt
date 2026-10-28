package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.ui.sensor.FirmwareWarning
import com.jakubfronczyk.northform.ui.sensor.NearbyCard
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Mode
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlin.time.Instant

/**
 * What Setup's Pair step shows and does, read from the Sensor screen's model: its [ui], Pair on a nearby sensor, and
 * [search] again after a scan that found nothing.
 */
class SetupPairing(val ui: SensorScreenModel.Ui, val pair: (SensorId) -> Unit, val search: () -> Unit)

/**
 * The live [SetupPairing]: one [SensorScreenModel] from [create], followed while this is composed. Called only from the
 * Pair step, so the model, its collectors and its scan live exactly as long as that step is on screen; `follow()` starts
 * the scan by itself when nothing is paired, so the step never starts a second one.
 */
@Composable
fun rememberSetupPairing(create: () -> SensorScreenModel): SetupPairing {
    val model = remember { create() }
    LaunchedEffect(model) { model.follow() }
    val ui by model.ui.collectAsState()
    return SetupPairing(ui, model::pair, model::connect)
}

/**
 * First-launch Setup (first-launch-setup spec, D71): About you → Two permissions → Pair your sensor → Your zones, then
 * Today. The steps are local state; the profile is saved on step 1 ([onSaveProfile]), so steps 2–4 can be left at any
 * time and done later from the gate, the Sensor sheet and Settings. [pairing] is composed only on the Pair step (see
 * [rememberSetupPairing]). [finish] closes Setup.
 */
@Composable
fun SetupScreen(
    profile: Profile?,
    now: Instant,
    tzOffset: Int,
    onSaveProfile: (Profile) -> Unit,
    permissions: SetupPermissions,
    allowBluetooth: () -> Unit,
    allowLocation: () -> Unit,
    openSettings: () -> Unit,
    pairing: @Composable () -> SetupPairing,
    finish: () -> Unit,
    /** The step Setup opens on: always About you in the app; previews open a later one. */
    start: SetupStep = SetupStep.AboutYou,
) {
    var step by remember { mutableStateOf(start) }
    var editingZones by remember { mutableStateOf(false) }
    val back: (() -> Unit)? = step.back()?.let { previous -> { step = previous } }
    fun next() { step.next()?.let { step = it } ?: finish() }
    when {
        editingZones && profile != null -> ZoneEditorScreen(profile, backLabel = "Back", onSave = { onSaveProfile(it); editingZones = false }, back = { editingZones = false })
        step == SetupStep.AboutYou -> SetupAboutYouScreen(
            initial = profile?.let(ProfileForm::of) ?: ProfileForm.blank, zones = profile?.zones ?: HRZones.standard, now = now, tzOffset = tzOffset,
            onContinue = { onSaveProfile(it); next() },
        ) { Progress(step, back = null) }
        step == SetupStep.Permissions -> SetupPage(step, back, "Two permissions", "Only what a run needs. Nothing else is asked now.", { LightButton("Continue", ::next) }) {
            Permissions(permissions, allowBluetooth, allowLocation, openSettings)
        }
        step == SetupStep.Pair -> {
            val p = pairing()
            // "Skip, pair later" until a sensor is paired (spec), then Continue; both go on.
            SetupPage(step, back, "Pair your sensor", "Turn on the Verity Sense and keep it close to the phone.", {
                LightButton(if (p.ui.paired != null) "Continue" else "Skip, pair later", ::next)
            }) { Pair(p) }
        }
        // Step 1 saved the profile before this step is reached; null only for the moment the save takes to land.
        profile != null -> SetupZones.of(profile).let { zones ->
            SetupPage(step, back, "Your zones", zones.subtitle, { BigButton("Start using the app", ::next, textColor = Theme.onPrimary) }) {
                Zones(zones) { editingZones = true }
            }
        }
    }
}

/** Steps 1–3's main button: light, as the artboards draw it (step 4's "Start using the app" is the primary). */
@Composable
private fun LightButton(title: String, onClick: () -> Unit) = BigButton(title, onClick, fill = Theme.light, textColor = Theme.onLight)

/** One Setup page: the progress, a title and subtitle, the step's content, and its [button] pinned at the bottom. */
@Composable
private fun SetupPage(step: SetupStep, back: (() -> Unit)?, title: String, subtitle: String, button: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PinnedScreen(button = button) {
        Progress(step, back)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Type.pageTitle, color = Theme.text)
            Text(subtitle, style = Type.secondaryText, color = Theme.text2)
        }
        content()
    }
}

/** "‹  ▬ ▬ ▬ ▬  2 of 4": Back (none on step 1), four segments lit up to this step, and the count. */
@Composable
private fun Progress(step: SetupStep, back: (() -> Unit)?) {
    Row(Modifier.height(Dimens.iconButton), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (back != null) BackButton("Back", back)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SetupStep.entries.forEach { s ->
                Box(Modifier.weight(1f).height(Dimens.progressSegment).clip(Shapes.progressSegment).background(if (s.ordinal <= step.ordinal) Theme.accent else Theme.track))
            }
        }
        Text(step.progress, style = Type.tertiaryText, color = Theme.text2)
    }
}

/** A Setup card: the card colour, 22 dp corners, [padding] inside. */
@Composable
private fun SetupCard(padding: Modifier = Modifier.padding(16.dp), content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).then(padding), content = content)

@Composable
private fun Permissions(model: SetupPermissions, allowBluetooth: () -> Unit, allowLocation: () -> Unit, openSettings: () -> Unit) {
    SetupCard(Modifier.padding(horizontal = 16.dp)) {
        PermissionRow(NfIcons.bluetooth, "Bluetooth", "To connect the armband.", model.bluetooth, allowBluetooth, openSettings)
        DashedLine()
        PermissionRow(NfIcons.location, "Location", "For map, distance and pace. While using the app, and in the background during a run.", model.location, allowLocation, openSettings)
    }
    model.locationNote?.let { Text(it, style = Type.tertiaryText, color = Theme.text2, modifier = Modifier.padding(horizontal = 4.dp)) }
    Text("Pick \"While Using the App\" when iOS asks. The app never asks for \"Always\".", style = Type.caption, color = Theme.text3, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, why: String, row: SetupPermissions.Row, allow: () -> Unit, openSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.factRow).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(Dimens.iconButton).background(Theme.track, CircleShape), contentAlignment = Alignment.Center) { NfIcon(icon, Theme.text, size = Dimens.icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyStrong, color = Theme.text)
            Text(why, style = Type.tertiaryText, color = Theme.text2)
            if (row.showsNotAllowed) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NfIcon(NfIcons.warning, Theme.warn, size = Dimens.iconTiny)
                    Text("Not allowed", style = Type.statusLabel, color = Theme.warn)
                }
            }
        }
        when {
            row.allowed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NfIcon(NfIcons.tick, Theme.ok, size = Dimens.iconSmall)
                Text("Allowed", style = Type.statusLabel, color = Theme.ok)
            }
            row.action == SetupPermissions.Action.Allow -> InlineButton("Allow", allow)
            row.action == SetupPermissions.Action.OpenSettings -> InlineButton("Open Settings", openSettings, filled = false)
        }
    }
}

/** The pairing card over the Sensor screen's model (E16): the paired sensor and its facts, or the nearby list to pair from. */
@Composable
private fun Pair(pairing: SetupPairing) {
    val ui = pairing.ui
    val paired = ui.paired
    when {
        paired != null -> PairedCard(paired, ui)
        ui.mode == Mode.BluetoothOff -> SetupCard { Text("Bluetooth is off. Turn it on in Control Center, or skip and pair later.", style = Type.secondaryText, color = Theme.text2) }
        ui.mode == Mode.Unauthorized -> SetupCard { Text("Bluetooth isn't allowed for this app. Allow it in iOS Settings, or skip and pair later.", style = Type.secondaryText, color = Theme.text2) }
        ui.mode == Mode.NotFound -> SetupCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("No Polar sensor answered in 30 s.", style = Type.bodyStrong, color = Theme.text)
                    Text("Press the sensor button until its light blinks, then search again.", style = Type.tertiaryText, color = Theme.text2)
                }
                InlineButton("Search again", pairing.search, filled = false)
            }
        }
        else -> NearbyCard(ui.nearby, pairing.pair, scanning = ui.mode == Mode.Searching || ui.nearby.isEmpty())
    }
    ui.error?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
}

/** `SetupPair.dc.html` paired: the sensor with "● Paired", its firmware and battery, and E16's warning inset when too old. No clock row (D17). */
@Composable
private fun PairedCard(paired: PairedSensorInfo, ui: SensorScreenModel.Ui) = SetupCard {
    Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(Dimens.iconButton).background(Theme.track, CircleShape), contentAlignment = Alignment.Center) { NfIcon(NfIcons.sensor, Theme.text, size = Dimens.icon) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(paired.displayName, style = Type.cardTitle, color = Theme.text)
            Text(paired.code, style = Type.tertiaryText, color = Theme.text2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(Dimens.statusDot).background(Theme.ok, CircleShape))
            Text("Paired", style = Type.statusLabel, color = Theme.ok)
        }
    }
    FactRow("Firmware", "2.1.0 or newer needed", ui.firmware, warn = ui.firmwareTooOld)
    FactRow("Battery", "read from the sensor", ui.battery?.let { "$it %" }, warn = false)
    if (ui.firmwareTooOld) Box(Modifier.padding(top = 4.dp)) { FirmwareWarning(ui.firmware ?: "", inset = true) }
}

/** A fact under the paired sensor: label and note, the value ("—" until read), a tick once read or a warning. */
@Composable
private fun FactRow(label: String, note: String, value: String?, warn: Boolean) {
    DashedLine()
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.factRow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = Type.rowLabel, color = Theme.text2)
            Text(note, style = Type.caption, color = Theme.text3)
        }
        Text(value ?: "—", style = Type.bodyStrong, color = if (warn) Theme.warn else Theme.text)
        when {
            warn -> NfIcon(NfIcons.warning, Theme.warn, size = Dimens.iconSmall, label = "Too old")
            value != null -> NfIcon(NfIcons.tick, Theme.ok, size = Dimens.iconSmall, label = "OK")
        }
    }
}

/** The zones table: each zone as its coloured pill, % of reserve, bpm; Edit boundaries opens the zone editor. */
@Composable
private fun Zones(model: SetupZones, edit: () -> Unit) {
    SetupCard {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZONE", style = Type.caption, color = Theme.text2, modifier = Modifier.width(Dimens.zoneColumn))
            Text("% HRR", style = Type.caption, color = Theme.text2, modifier = Modifier.weight(1f))
            Text("BPM", style = Type.caption, color = Theme.text2, textAlign = TextAlign.End, modifier = Modifier.width(Dimens.bpmColumn))
        }
        model.rows.forEachIndexed { zone, row ->
            DashedLine()
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.zoneRow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(Dimens.zoneColumn)) {
                    Box(Modifier.height(Dimens.zonePill).clip(Shapes.capsule).background(Theme.zoneColors[zone]).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Text(row.label, style = Type.zonePill, color = Theme.onZone(zone), maxLines = 1)
                    }
                }
                Text(row.percent, style = Type.rowLabel, color = Theme.text2, modifier = Modifier.weight(1f))
                Text(row.bpm, style = Type.zonePercent, color = Theme.text, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(Dimens.bpmColumn))
            }
        }
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(Dimens.pill).clip(Shapes.pill).border(Dimens.stroke, Theme.outline, Shapes.pill).clickable(onClick = edit),
            contentAlignment = Alignment.Center,
        ) { Text("Edit boundaries", style = Type.secondaryStrong, color = Theme.text) }
    }
    Text("Boundaries are kept as %. If your max changes, the bpm moves with it.", style = Type.caption, color = Theme.text3, modifier = Modifier.padding(horizontal = 4.dp))
}
