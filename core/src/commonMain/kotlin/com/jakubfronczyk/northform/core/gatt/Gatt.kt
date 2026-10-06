package com.jakubfronczyk.northform.core.gatt

import com.jakubfronczyk.northform.core.ports.GattUuid

/**
 * The standard services the Verity Sense exposes and v1.0 reads (D8). Assigned numbers:
 * https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/service_uuids.yaml
 * https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/uuids/characteristic_uuids.yaml
 * Polar's own SDK reads HR over exactly these (BleHrClient.swift: "180D"/"2a37"; BleBasClient.swift: "180F"/"2A19").
 */
object Gatt {
    val heartRateService = GattUuid("180D")
    val heartRateMeasurement = GattUuid("2A37")

    val batteryService = GattUuid("180F")
    val batteryLevel = GattUuid("2A19")

    val deviceInformationService = GattUuid("180A")
    val firmwareRevision = GattUuid("2A26")
    val manufacturerName = GattUuid("2A29")
    val modelNumber = GattUuid("2A24")

    /** What `SensorLink` asks the transport to discover on every connect. */
    val servicesUsed = listOf(heartRateService, batteryService, deviceInformationService)
}
