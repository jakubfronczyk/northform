package com.jakubfronczyk.northform.ui.sensor

import kotlin.test.Test
import kotlin.test.assertEquals

class NearbyRowTest {
    @Test
    fun a_found_sensor_shows_its_signal_only_while_the_scan_runs() {
        assertEquals("A1B2C3D4 · -62 dBm", nearbyDetail("A1B2C3D4", rssi = -62, scanning = true))
        assertEquals("A1B2C3D4", nearbyDetail("A1B2C3D4", rssi = -62, scanning = false), "D34: a last-seen value is not current")
    }
}
