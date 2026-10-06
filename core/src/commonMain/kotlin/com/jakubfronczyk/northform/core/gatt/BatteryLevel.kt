package com.jakubfronczyk.northform.core.gatt

/**
 * Battery Level characteristic (0x2A19): one uint8, 0–100 %. Read is mandatory, Notify optional
 * (Battery Service 1.1, table 3.1: https://www.bluetooth.com/specifications/specs/battery-service-1-1/).
 * Whether the Verity Sense offers Notify is spike step 2 (U3); if not, `SensorLink` polls.
 */
object BatteryLevel {
    fun parse(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val level = bytes[0].toInt() and 0xFF
        return level.takeIf { it in 0..100 }
    }
}

/** Device Information strings (0x2A26 firmware, 0x2A29 manufacturer, 0x2A24 model) are UTF-8, no framing. */
object DeviceInformation {
    fun parseString(bytes: ByteArray): String = bytes.decodeToString().trimEnd('\u0000')

    /** "2.1.0" → [2, 1, 0]; used by the E16/SU3 firmware check (≥ 2.1.0). Null if not dotted digits. */
    fun parseVersion(text: String): List<Int>? =
        text.trim().split('.').map { it.toIntOrNull() ?: return null }
}
