package com.jakubfronczyk.northform.engine.sensor

import com.jakubfronczyk.northform.core.SensorId

/**
 * What the sensor link is doing right now, for the spike screen and device logs. A conflatable view
 * (StateFlow), derived from `SensorLink.State` after every reduce — the UI renders it and decides nothing.
 */
data class LinkDiagnostics(
    /** Raw platform code; CoreBluetooth: 0 unknown · 1 resetting · 2 unsupported · 3 unauthorized · 4 poweredOff · 5 poweredOn. */
    val centralState: Int? = null,
    val centralReady: Boolean = false,
    val scanWanted: Boolean = false,
    val scanning: Boolean = false,
    val wanted: SensorId? = null,
    val connected: SensorId? = null,
    val ready: Boolean = false,
    val hrNotifying: Boolean = false,
    val listeners: Int = 0,
    val battery: Int? = null,
    val firmware: String? = null,
    /** How many radio events the mailbox has reduced, and the last one, so a stalled link is visible. */
    val events: Int = 0,
    val lastEvent: String? = null,
) {
    val centralStateLabel: String
        get() = when (centralState) {
            null -> "waiting for first callback"
            0 -> "unknown (0)"
            1 -> "resetting (1)"
            2 -> "unsupported (2)"
            3 -> "unauthorized (3)"
            4 -> "poweredOff (4)"
            5 -> "poweredOn (5)"
            else -> "code $centralState"
        }
}

internal fun SensorLink.State.toDiagnostics(events: Int, lastEvent: String?) = LinkDiagnostics(
    centralState = centralState,
    centralReady = centralReady,
    scanWanted = scanWanted,
    scanning = scanning,
    wanted = wanted,
    connected = connected,
    ready = ready,
    hrNotifying = hrNotifying,
    listeners = listeners,
    battery = battery,
    firmware = firmware,
    events = events,
    lastEvent = lastEvent,
)
