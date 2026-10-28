package com.jakubfronczyk.northform.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.SetupPermissions.Location
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Mode
import kotlin.time.Instant

// First-launch Setup (first-launch-setup spec), one preview per step and state of `Setup*.dc.html`.

private val saved = Profile(Instant.parse("1996-03-14T00:00:00Z"), Sex.Male, 78.0, 182.0, hrMax = 187, hrRest = 60)
private val searching = SensorScreenModel.Ui(Mode.Searching, nearby = listOf(DiscoveredSensor(SensorId("A1B2C3D4"), "Polar Sense A1B2C3D4", -58)))
private val paired = SensorScreenModel.Ui(Mode.Connected, paired = PairedSensorInfo(SensorId("A1B2C3D4"), "Polar Sense A1B2C3D4"), battery = 82, firmware = "2.2.1")

@Composable
private fun preview(
    start: SetupStep, profile: Profile? = saved, bluetooth: Boolean = true, location: Location = Location.NotAsked, sensor: SensorScreenModel.Ui = searching,
) = SetupScreen(
    profile, previewNow, PREVIEW_TZ, onSaveProfile = {}, permissions = SetupPermissions.of(bluetooth, location), allowLocation = {}, openSettings = {},
    pairing = { SetupPairing(sensor, pair = {}, search = {}) }, finish = {}, start = start,
)

@Preview @Composable fun SetupAboutYouPreview() = preview(SetupStep.AboutYou, profile = null)
@Preview @Composable fun SetupAboutYouAgainPreview() = preview(SetupStep.AboutYou)

@Preview @Composable fun SetupPermissionsAskPreview() = preview(SetupStep.Permissions)
@Preview @Composable fun SetupPermissionsAllowedPreview() = preview(SetupStep.Permissions, location = Location.Allowed)
@Preview @Composable fun SetupPermissionsDeniedPreview() = preview(SetupStep.Permissions, bluetooth = false, location = Location.Denied)

@Preview @Composable fun SetupPairSearchingPreview() = preview(SetupStep.Pair)
@Preview @Composable fun SetupPairPairedPreview() = preview(SetupStep.Pair, sensor = paired)
@Preview @Composable fun SetupPairOldFirmwarePreview() = preview(SetupStep.Pair, sensor = paired.copy(firmware = "2.0.4", firmwareTooOld = true))
@Preview @Composable fun SetupPairNotFoundPreview() = preview(SetupStep.Pair, sensor = SensorScreenModel.Ui(Mode.NotFound))

@Preview @Composable fun SetupZonesPreview() = preview(SetupStep.Zones)
