package com.jakubfronczyk.northform.core.gatt

/**
 * Heart Rate Measurement characteristic (0x2A37), decoded per the Bluetooth SIG GATT Specification
 * Supplement, "Heart Rate Measurement":
 *   Flags (1 octet):  bit 0  value format      0 = uint8, 1 = uint16
 *                     bit 1  sensor contact detected
 *                     bit 2  sensor contact supported
 *                     bit 3  Energy Expended present (uint16, kJ)
 *                     bit 4  RR-Interval present (zero or more uint16, resolution 1/1024 s, oldest first)
 *   https://bitbucket.org/bluetooth-SIG/public/raw/main/gss/org.bluetooth.characteristic.heart_rate_measurement.yaml
 * Polar's parser does the same (polar-ble-sdk, BleHrClient.swift:25-54). Pure, tested on bytes.
 *
 * v1.0 uses `bpm` only. RR intervals are decoded for completeness (the Verity Sense is not documented
 * to set bit 4 — Polar lists RR for the H10 only; PPI comes over PMD, which is v1.2).
 */
data class HeartRateMeasurement(
    val bpm: Int,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean,
    val energyExpendedKj: Int?,
    /** RR intervals in milliseconds, oldest first. */
    val rrIntervalsMs: List<Int>,
) {
    companion object {
        private const val FLAG_UINT16 = 0x01
        private const val FLAG_CONTACT_DETECTED = 0x02
        private const val FLAG_CONTACT_SUPPORTED = 0x04
        private const val FLAG_ENERGY = 0x08
        private const val FLAG_RR = 0x10

        /** Returns null for a frame too short to hold what its flags announce (never throws on radio bytes). */
        fun parse(bytes: ByteArray): HeartRateMeasurement? {
            if (bytes.isEmpty()) return null
            val flags = bytes[0].toInt() and 0xFF
            var i = 1

            val bpm: Int
            if (flags and FLAG_UINT16 != 0) {
                if (bytes.size < i + 2) return null
                bpm = bytes.u16(i); i += 2
            } else {
                if (bytes.size < i + 1) return null
                bpm = bytes[i].toInt() and 0xFF; i += 1
            }

            var energy: Int? = null
            if (flags and FLAG_ENERGY != 0) {
                if (bytes.size < i + 2) return null
                energy = bytes.u16(i); i += 2
            }

            val rr = mutableListOf<Int>()
            if (flags and FLAG_RR != 0) {
                while (bytes.size >= i + 2) {
                    // 1/1024 s units → ms, rounded; matches Polar's `rrsMs` conversion.
                    rr += (bytes.u16(i) * 1000 + 512) / 1024
                    i += 2
                }
            }

            return HeartRateMeasurement(
                bpm = bpm,
                sensorContactSupported = flags and FLAG_CONTACT_SUPPORTED != 0,
                sensorContactDetected = flags and FLAG_CONTACT_DETECTED != 0,
                energyExpendedKj = energy,
                rrIntervalsMs = rr,
            )
        }

        private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    }
}
