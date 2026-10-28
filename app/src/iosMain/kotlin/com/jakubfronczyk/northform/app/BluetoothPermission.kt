package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.ble.BluetoothAuthorization
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.ui.SetupPermissions

/** D9 step 2 (ADR 0005, amended): boot's half of the rule. Setup's Allow creates the central otherwise. */
internal fun CoreBluetoothCentral.activateAtBoot(authorization: BluetoothAuthorization) {
    if (authorization.decided) activate()
}

/** The platform's Bluetooth authorization in Setup's words; `:composeApp` never sees `:adapters` (D15). */
internal fun BluetoothAuthorization.asPermission(): SetupPermissions.Bluetooth = when (this) {
    BluetoothAuthorization.NotDetermined -> SetupPermissions.Bluetooth.NotAsked
    BluetoothAuthorization.Allowed -> SetupPermissions.Bluetooth.Allowed
    BluetoothAuthorization.Denied -> SetupPermissions.Bluetooth.Denied
    BluetoothAuthorization.Restricted -> SetupPermissions.Bluetooth.Restricted
}
