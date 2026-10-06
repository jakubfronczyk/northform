package com.jakubfronczyk.northform.core.gatt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryLevelTest {
    @Test
    fun percent_is_one_unsigned_byte() {
        assertEquals(90, BatteryLevel.parse(byteArrayOf(0x5A)))
        assertEquals(0, BatteryLevel.parse(byteArrayOf(0)))
        assertEquals(100, BatteryLevel.parse(byteArrayOf(100)))
    }

    @Test
    fun out_of_range_or_empty_is_null() {
        assertNull(BatteryLevel.parse(byteArrayOf(101)))
        assertNull(BatteryLevel.parse(byteArrayOf(0xFF.toByte())))
        assertNull(BatteryLevel.parse(ByteArray(0)))
    }

    @Test
    fun firmware_revision_string_and_version() {
        assertEquals("2.1.0", DeviceInformation.parseString("2.1.0".encodeToByteArray()))
        assertEquals(listOf(2, 1, 0), DeviceInformation.parseVersion("2.1.0"))
        assertNull(DeviceInformation.parseVersion("beta"))
    }
}
