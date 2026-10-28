package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.ble.BluetoothAuthorization
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.ports.GattUuid
import com.jakubfronczyk.northform.ui.SetupPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR 0005 (amended 2026-10-28): the `CBCentralManager` is created at boot only once iOS has an answer about Bluetooth;
 * before that, Setup's Allow creates it, which is what makes iOS ask.
 */
class BluetoothAuthorizationTest {

    /** Restoration needs the central at launch, and it can only matter after an answer (no sensor pairs without one). */
    @Test
    fun only_an_unanswered_authorization_leaves_the_central_asleep_at_boot() {
        assertEquals(listOf(BluetoothAuthorization.NotDetermined), BluetoothAuthorization.entries.filter { !it.decided })
    }

    /** The boot step itself, for each answer iOS can give (the simulator's own answer is fixed, so BootTest sees only one). */
    @Test
    fun boot_creates_the_central_only_when_ios_has_an_answer() {
        for (authorization in BluetoothAuthorization.entries) {
            val central = CoreBluetoothCentral("com.jakubfronczyk.northform.test")
            central.activateAtBoot(authorization)
            assertEquals(authorization != BluetoothAuthorization.NotDetermined, central.isActive, "$authorization")
        }
    }

    /** One mapping drives Setup's Bluetooth row. */
    @Test
    fun each_authorization_maps_to_setup_s_bluetooth_row() {
        val table = mapOf(
            BluetoothAuthorization.NotDetermined to SetupPermissions.Bluetooth.NotAsked,
            BluetoothAuthorization.Allowed to SetupPermissions.Bluetooth.Allowed,
            BluetoothAuthorization.Denied to SetupPermissions.Bluetooth.Denied,
            BluetoothAuthorization.Restricted to SetupPermissions.Bluetooth.Restricted,
        )
        assertEquals(BluetoothAuthorization.entries.toSet(), table.keys, "every state is mapped")
        for ((authorization, bluetooth) in table) assertEquals(bluetooth, authorization.asPermission(), "$authorization")
    }

    /**
     * Until [CoreBluetoothCentral.activate], there is no `CBCentralManager`, so nothing can make iOS ask: every command
     * is dropped (the link re-issues what it still wants on the first power-on) and no peripheral is known.
     */
    @Test
    fun the_central_does_nothing_until_activated_and_activates_once() {
        val central = CoreBluetoothCentral("com.jakubfronczyk.northform.test")
        val id = SensorId("6F3B2A10-0000-4000-8000-000000000001")
        val hr = GattUuid("2A37")
        assertFalse(central.isActive)
        assertFalse(central.isKnown(id))
        central.scan(listOf(GattUuid("180D")))
        central.stopScan()
        central.connect(id, autoReconnect = true)
        central.cancelConnect(id)
        central.discover(id, listOf(GattUuid("180D")))
        central.setNotify(id, hr, on = true)
        central.read(id, hr)
        assertFalse(central.isActive, "no command creates the central")

        central.activate()
        assertTrue(central.isActive)
        central.activate() // idempotent: Setup's Allow after boot already activated it
        assertTrue(central.isActive)
    }
}
