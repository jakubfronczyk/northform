package com.jakubfronczyk.northform.core.reliability

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.ports.RecordingData
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The reliability log, per recording (R29, D45 of the Swift build): what went wrong with the signals
 * during a run, counted from the events the reducer recorded. A pure function of [RecordingData], so
 * it can be recomputed from the store at any time and never disagrees with the raw data; the `reliability`
 * row (`:persistence`) is a cache of it, stamped with [version] so a rule change can recompute old rows.
 *
 * Vocabulary (GLOSSARY of the Swift build, "Signal loss"): a **drop** is the Bluetooth link lost without
 * the user asking (`SensorLost`; a user disconnect records nothing), a **reconnect** is the link coming back
 * (`SensorReconnected`), HR/GPS **lost seconds** are the sum of every Lost → Back interval, a **crash** is the app
 * reopened after a crash (`CrashRecovered`, written whatever the user chose, D32). **Bluetooth off** (`BluetoothOff`, D31) is the radio switched
 * off, counted apart and never as a drop; the link coming back after it is a reconnect with no reconnect time
 * (no drop was open).
 */
data class ReliabilityRecord(
    val drops: Int,
    val reconnects: Int,
    /** One entry per reconnect that followed a recorded drop, in run order: `SensorReconnected.t − SensorLost.t`. */
    val reconnectTimes: List<Duration>,
    val hrLost: Duration,
    val gpsLost: Duration,
    /** `CrashRecovered` events (D32): every crash the run went through, continued or saved. */
    val crashes: Int,
    /** `BluetoothOff` events (D31): the radio was off, not a drop. */
    val bluetoothOff: Int = 0,
) {
    val reconnectTotal: Duration get() = reconnectTimes.fold(Duration.ZERO) { a, b -> a + b }
    val reconnectLongest: Duration get() = reconnectTimes.maxOrNull() ?: Duration.ZERO

    companion object {
        /**
         * Bumped when a counting rule changes, so stored rows can be told apart from fresh ones.
         * 2 (D31, D32): Bluetooth-off is counted apart, no longer as a drop; `recoveries` became `crashes`.
         */
        const val version = 2
        val none = ReliabilityRecord(0, 0, emptyList(), Duration.ZERO, Duration.ZERO, 0)

        /**
         * Events are read in stored order (the reducer's order, not re-sorted: a drop and its `Connecting`
         * share one instant). A loss still open at the end closes at the latest moment the store knows about:
         * the heartbeat (`lastPersistedAt`, never older than the data it covers — ADR 0006), else the stop, else
         * the last event. A `Back` with no `Lost` open, and a second `Lost` while one is open, change nothing.
         *
         * @param closeAt overrides that end, and nothing after it counts: an event later than [closeAt] is
         *   skipped, so a `Back` in the cool-down cannot stretch a loss past it and a drop there is no drop.
         *   The run summary's "Data ·" line passes the stop: it describes the effort, and the cool-down is
         *   not part of it. A `CrashRecovered` counts wherever it lies (D40, D32): a Save after a crash writes it
         *   at the answer, after the run's end (its last heartbeat, D30), and that run still went through a crash.
         */
        fun of(data: RecordingData, closeAt: Instant? = null): ReliabilityRecord {
            val end = closeAt ?: data.lastPersistedAt ?: data.recording.endUtc ?: data.events.lastOrNull()?.t ?: data.recording.startUtc
            var drops = 0
            var reconnects = 0
            var crashes = 0
            var bluetoothOff = 0
            val reconnectTimes = ArrayList<Duration>()
            var dropOpenedAt: Instant? = null
            val hr = Loss()
            val gps = Loss()
            for (event in data.events) {
                val t = event.t
                if (event.kind == EventKind.CrashRecovered) { crashes += 1; continue }
                if (closeAt != null && t > closeAt) continue
                when (event.kind) {
                    EventKind.SensorLost -> { drops += 1; if (dropOpenedAt == null) dropOpenedAt = t }
                    EventKind.BluetoothOff -> bluetoothOff += 1
                    EventKind.SensorReconnected -> {
                        reconnects += 1
                        dropOpenedAt?.let { reconnectTimes += (t - it).coerceAtLeast(Duration.ZERO) }
                        dropOpenedAt = null
                    }
                    EventKind.HrLost -> hr.lost(t)
                    EventKind.HrBack -> hr.back(t)
                    EventKind.GpsLost -> gps.lost(t)
                    EventKind.GpsBack -> gps.back(t)
                    EventKind.CrashRecovered -> Unit // counted above, whatever closeAt says
                    EventKind.Pause, EventKind.Resume, is EventKind.Split, EventKind.Stop, EventKind.CooldownEnd -> Unit
                }
            }
            return ReliabilityRecord(
                drops = drops,
                reconnects = reconnects,
                reconnectTimes = reconnectTimes,
                hrLost = hr.total(end),
                gpsLost = gps.total(end),
                crashes = crashes,
                bluetoothOff = bluetoothOff,
            )
        }

        /** One signal's Lost → Back bookkeeping; a mutable builder that lives inside `of` only (the pure function's privacy). */
        private class Loss {
            private var openedAt: Instant? = null
            private var sum = Duration.ZERO
            fun lost(t: Instant) { if (openedAt == null) openedAt = t }
            fun back(t: Instant) { openedAt?.let { sum += (t - it).coerceAtLeast(Duration.ZERO) }; openedAt = null }
            fun total(end: Instant): Duration = sum + (openedAt?.let { (end - it).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO)
        }
    }
}
