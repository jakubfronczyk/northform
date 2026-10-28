package com.jakubfronczyk.northform.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import kotlin.time.Instant

// Settings (profile-settings/02) on the artboard's sample values.

private val profile = Profile(birthDate = Instant.parse("1996-03-14T00:00:00Z"), sex = Sex.Male, weightKg = 78.0, heightCm = 182.0, hrMax = 187, hrRest = 60)
private val verity = PairedSensorInfo(SensorId("A1B2C3D4"), "Polar Sense A1B2C3D4")

@Composable
private fun preview(sensor: PairedSensorInfo? = verity, export: ExportState = ExportState.Idle) = SettingsScreen(
    SettingsModel.from(profile, previewNow, PREVIEW_TZ, sensor, battery = 82, firmware = "2.2.1", export, version = "Version 1.0 (14)"),
    back = {}, editProfile = {}, editZones = {}, export = {}, forgetSensor = {},
)

@Preview @Composable fun SettingsPreview() = preview()
@Preview @Composable fun SettingsNoSensorPreview() = preview(sensor = null)
@Preview @Composable fun SettingsExportedPreview() = preview(export = ExportState.Saved("northform-backup-20261028-1200.json"))
@Preview @Composable fun SettingsExportFailedPreview() = preview(export = ExportState.Failed("disk full"))

// The profile editor (profile-settings/03).
@Preview @Composable fun ProfileEditPreview() = ProfileEditScreen(profile, previewNow, PREVIEW_TZ, onSave = {}, back = {})
@Preview @Composable fun ProfileEditInvalidPreview() = ProfileEditScreen(profile.copy(hrMax = 75, hrRest = 60), previewNow, PREVIEW_TZ, onSave = {}, back = {})
