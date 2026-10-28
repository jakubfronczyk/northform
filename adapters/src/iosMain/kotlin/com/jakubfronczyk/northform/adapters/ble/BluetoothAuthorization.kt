package com.jakubfronczyk.northform.adapters.ble

import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

/**
 * What iOS says about this app and Bluetooth (`CBManager.authorization`, a class property, iOS 13.1+): reading it never
 * prompts, unlike creating a `CBCentralManager`. `Denied` the person can undo in Settings; `Restricted` (Screen Time,
 * a profile) they cannot.
 */
enum class BluetoothAuthorization {
    NotDetermined, Allowed, Denied, Restricted;

    /**
     * iOS has an answer, so creating the central cannot prompt. ADR 0005 (amended): boot creates the central only then,
     * which keeps state restoration for every install that could have a paired sensor.
     */
    val decided: Boolean get() = this != NotDetermined

    companion object {
        fun current(): BluetoothAuthorization = of(CBManager.authorization)

        fun of(authorization: CBManagerAuthorization) = when (authorization) {
            CBManagerAuthorizationNotDetermined -> NotDetermined
            CBManagerAuthorizationAllowedAlways -> Allowed
            CBManagerAuthorizationRestricted -> Restricted
            else -> Denied
        }
    }
}
