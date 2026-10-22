package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.RunSummary
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Previews only: a 52:40 run and a 40:00 walk as the store would hold them after the cool-down, run
 * through the real [RunSummary.compute] so every preview number is one the engine would show. The
 * run has one sensor drop (12 s) and a 10 s pause; the walk has no heart rate at all.
 */
object FakeSummary {
    private val start = Instant.parse("2025-09-30T05:12:00Z")
    private val profile = Profile(Instant.parse("1990-01-01T00:00:00Z"), Sex.Male, weightKg = 75.0, heightCm = 180.0, hrMax = 187, hrRest = 52, zones = HRZones.standard)

    private fun data(type: RecordingType, seconds: Int, metresPerSecond: Double, hr: ((Int) -> Int)?): RecordingData {
        val stop = start + seconds.seconds
        val drop = 1201..1211 // no samples: the line's gap runs 1200 → 1212, "sensor gap 12 s"
        val events = buildList {
            add(RecordingEvent(start + 1500.seconds, EventKind.Pause, EventSource.Manual))
            add(RecordingEvent(start + 1510.seconds, EventKind.Resume, EventSource.Manual))
            if (hr != null) {
                add(RecordingEvent(start + drop.first.seconds, EventKind.SensorLost, EventSource.Auto))
                add(RecordingEvent(start + (drop.first + 5).seconds, EventKind.HrLost, EventSource.Auto))
                add(RecordingEvent(start + (drop.last + 1).seconds, EventKind.SensorReconnected, EventSource.Auto))
                add(RecordingEvent(start + (drop.last + 1).seconds, EventKind.HrBack, EventSource.Auto))
            }
            for (km in 1..(seconds * metresPerSecond / 1000).toInt()) add(RecordingEvent(start + (km * 1000 / metresPerSecond).seconds, EventKind.Split(km), EventSource.Auto))
            add(RecordingEvent(stop, EventKind.Stop, EventSource.Manual))
            add(RecordingEvent(stop + 60.seconds, EventKind.CooldownEnd, EventSource.Auto))
        }
        val fixes = (0..seconds).map { s ->
            val moving = if (s in 1500..1510) 1500 else s - (if (s > 1510) 10 else 0)
            LocationFix(t = start + s.seconds, latitude = 52.5145 + moving * metresPerSecond / 111_000, longitude = 13.3501, horizontalAccuracy = 5.0, altitude = 40.0 + 6 * sin(s / 300.0))
        }
        val samples = if (hr == null) emptyList() else (0..seconds + 60).filter { it !in drop }.map { s -> HrSample(start + s.seconds, hr(s)) }
        return RecordingData(
            recording = Recording(id = RecordingId("preview-$type"), type = type, state = RecordingState.Computing, startUtc = start, tzOffset = 7200, endUtc = stop),
            hr = samples, fixes = fixes, events = events, lastPersistedAt = stop + 60.seconds,
        )
    }

    /** 52:40, 10.2 km, HR climbing 128 → 171 then dropping through the cool-down. */
    val run: RunSummary = RunSummary.compute(
        data(RecordingType.Run, 52 * 60 + 40, metresPerSecond = 3.23) { s -> if (s <= 3160) 128 + (s * 43 / 3160) else 171 - ((s - 3160) * 31 / 60) },
        profile,
    )

    /** The run with the cool-down skipped at once: "1 min after stop — skipped". */
    val skippedCooldown: RunSummary = run.let {
        val d = data(RecordingType.Run, 52 * 60 + 40, metresPerSecond = 3.23) { s -> if (s <= 3160) 128 + (s * 43 / 3160) else 171 }
        RunSummary.compute(d.copy(events = d.events.map { e -> if (e.kind == EventKind.CooldownEnd) e.copy(t = e.t - 58.seconds) else e }), profile)
    }

    /** The run with 30 s at 191 bpm near the end: the "New max heart rate: 191" card (SU6). */
    val newHrMax: RunSummary = RunSummary.compute(
        data(RecordingType.Run, 52 * 60 + 40, metresPerSecond = 3.23) { s -> if (s in 3000..3030) 191 else if (s <= 3160) 128 + (s * 43 / 3160) else 171 - ((s - 3160) * 31 / 60) },
        profile,
    )

    /** 40:00, 3.8 km, no sensor paired. */
    val walk: RunSummary = RunSummary.compute(data(RecordingType.Walk, 40.minutes.inWholeSeconds.toInt(), metresPerSecond = 1.6, hr = null), profile)
}
