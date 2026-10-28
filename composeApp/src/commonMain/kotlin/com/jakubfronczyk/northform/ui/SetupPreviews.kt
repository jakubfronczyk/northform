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
import kotlin.time.Instant

// First-launch Setup (first-launch-setup spec), one preview per step and state.

private val saved = Profile(Instant.parse("1996-03-14T00:00:00Z"), Sex.Male, 78.0, 182.0, hrMax = 187, hrRest = 60)
private val searching = SensorScreenModel.Ui(mode = SensorScreenModel.Mode.Searching, nearby = listOf(DiscoveredSensor(SensorId("A1B2C3D4"), "Polar Sense A1B2C3D4", -58)))
private val paired = SensorScreenModel.Ui(mode = SensorScreenModel.Mode.Connected, paired = PairedSensorInfo(SensorId("A1B2C3D4"), "Polar Sense A1B2C3D4"), battery = 82, firmware = "2.2.1")

/** [SetupScreen] opens on step 1; previews of later steps render the step's own page through the same pieces. */
@Composable
private fun preview(profile: Profile? = null, location: Location = Location.NotAsked, sensor: SensorScreenModel.Ui = searching) = SetupScreen(
    profile, previewNow, PREVIEW_TZ, onSaveProfile = {}, permissions = SetupPermissions.of(true, location), allowLocation = {}, openSettings = {},
    sensor = sensor, search = {}, pair = {}, finish = {},
)

@Preview @Composable fun SetupAboutYouPreview() = preview()
@Preview @Composable fun SetupAboutYouAgainPreview() = preview(saved)
