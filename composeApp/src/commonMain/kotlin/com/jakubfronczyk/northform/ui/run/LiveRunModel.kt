package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.metrics.Zone
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.engine.run.ShownPace
import com.jakubfronczyk.northform.ui.RunFormat
import kotlin.time.Duration
import kotlin.time.Instant

/** The artboards' placeholder for a number that is not there (LiveDark: pace while GPS is lost, HR while the sensor is lost). */
internal const val NO_VALUE = "—"

/**
 * Everything the LiveDark artboard shows, as plain values: one pure function of the snapshot and the
 * moment it is drawn, so the screen only lays out and the rules are table-tested without Compose.
 * Port of `LiveRunView.swift:16-85` plus the design's paused pill, zone bar and Avg/Max row (H1).
 */
data class LiveRunModel(
    /** "Paused · 0:42" — how long the run has been paused; null while running. */
    val pausedFor: Duration?,
    /** Current pace (D106), rounded to 5 s/km (D22); [NO_VALUE] while paused, under 20 m, or with GPS lost. */
    val pace: String,
    val paceDimmed: Boolean,
    /** Split pace of the current km; the secondary line (D106). */
    val split: String,
    val hr: Hr,
    /** Running averages survive a sensor drop; [NO_VALUE] only before the first sample. */
    val hrAvg: String,
    val hrMax: String,
    val distance: String,
    /** "on hold" while GPS is lost (distance stops counting), else "". */
    val distanceSub: String,
    val time: String,
    val gpsLost: Boolean,
) {
    val paused: Boolean get() = pausedFor != null
    /** Paused dims every value but the time. */
    val valuesDimmed: Boolean get() = paused

    /** The HR cell: never a stale number (`LiveRunView.swift:70`). */
    sealed interface Hr {
        data object Lost : Hr
        data class Reading(val bpm: String, val zone: Zone?) : Hr
    }

    /** Which zone-bar segment is lit; none while the sensor is lost. */
    val litZone: Int? get() = (hr as? Hr.Reading)?.zone?.number

    companion object {
        /** How long a pause that began at [since] has lasted at [now]; never negative. */
        fun pausedFor(since: Instant, now: Instant): Duration = (now - since).coerceAtLeast(Duration.ZERO)

        fun from(snapshot: RunSnapshot, now: Instant): LiveRunModel {
            val paused = snapshot.phase as? RunPhase.Paused
            val gpsLost = snapshot.gpsLost
            val shownPace = ShownPace.round(snapshot.currentPace)
            return LiveRunModel(
                pausedFor = paused?.let { pausedFor(it.since, now) },
                pace = if (gpsLost || shownPace == null) NO_VALUE else RunFormat.pace(shownPace),
                paceDimmed = gpsLost || paused != null,
                split = if (gpsLost || snapshot.splitPace == null) NO_VALUE else RunFormat.pace(snapshot.splitPace),
                hr = if (snapshot.hrLost) Hr.Lost else Hr.Reading(snapshot.hrNow?.toString() ?: NO_VALUE, snapshot.zone),
                hrAvg = snapshot.hrAvg?.toString() ?: NO_VALUE,
                hrMax = snapshot.hrMax?.toString() ?: NO_VALUE,
                distance = RunFormat.distance(snapshot.distance),
                distanceSub = if (gpsLost) "on hold" else "",
                time = RunFormat.time(snapshot.totalTime),
                gpsLost = gpsLost,
            )
        }
    }
}
