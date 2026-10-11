package com.jakubfronczyk.northform.ui.sensor

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel.Mode

// One preview per artboard variant (`Sensor.dc.html` modes + `oldFirmware`), from plain values.

private val paired = FakeSensorDetails.sample
private val connected = SensorScreenModel.Ui(Mode.Connected, paired, bpm = 74, battery = 82, firmware = "2.2.1")

@Composable
private fun preview(ui: SensorScreenModel.Ui) = SensorScreenContent(ui, connect = {}, pair = {}, cancel = {}, disconnect = {}, close = {})

@Preview @Composable fun SensorConnectedPreview() = preview(connected)
@Preview @Composable fun SensorOldFirmwarePreview() = preview(connected.copy(firmware = "2.0.4", firmwareTooOld = true))
@Preview @Composable fun SensorDisconnectedPreview() = preview(SensorScreenModel.Ui(Mode.Disconnected, paired, firmware = "2.2.1"))
@Preview @Composable fun SensorSearchingPreview() = preview(SensorScreenModel.Ui(Mode.Searching, paired))
@Preview @Composable fun SensorNotFoundPreview() = preview(SensorScreenModel.Ui(Mode.NotFound, paired))
@Preview @Composable fun SensorNothingPairedPreview() = preview(
    SensorScreenModel.Ui(Mode.Searching, paired = null, nearby = listOf(DiscoveredSensor(SensorId("9B2E0B20-7E0A-4C5B-8A2E-0D3B2C4F5A61"), "Polar Sense A1B2C3D4", -62))),
)
@Preview @Composable fun SensorBluetoothOffPreview() = preview(SensorScreenModel.Ui(Mode.BluetoothOff, paired))
@Preview @Composable fun SensorUnauthorizedPreview() = preview(SensorScreenModel.Ui(Mode.Unauthorized, paired))
