package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.RecordingEvent
import kotlin.time.Duration
import kotlin.time.Instant

/** One manual pause, `[from, until)` (D33). */
data class Pause(val from: Instant, val until: Instant) {
    val duration: Duration get() = until - from

    /** How much of `[start, end)` was paused. */
    fun overlap(start: Instant, end: Instant): Duration = maxOf(Duration.ZERO, minOf(until, end) - maxOf(from, start))
}

/**
 * The pauses of a stored recording, and active time (total − paused, spec 08 "Active time") read
 * from them: the summary's twin of the live `RunProgress.activeTime`, computed from the same
 * events the reducer recorded, so the two agree on every split (D121).
 */
object Pauses {
    /** `Pause`/`Resume` events in order; a pause still open at [stoppedAt] ends there (hold-to-stop while paused closes it). */
    fun intervals(events: List<RecordingEvent>, stoppedAt: Instant): List<Pause> {
        val pauses = ArrayList<Pause>()
        var open: Instant? = null
        for (event in events) {
            when (event.kind) {
                EventKind.Pause -> if (open == null) open = event.t
                EventKind.Resume -> open?.let { pauses += Pause(it, maxOf(it, event.t)); open = null }
                else -> Unit
            }
        }
        open?.let { pauses += Pause(it, maxOf(it, stoppedAt)) }
        return pauses
    }

    /** Paused time inside `[start, end)`. */
    fun pausedWithin(pauses: List<Pause>, start: Instant, end: Instant): Duration =
        pauses.fold(Duration.ZERO) { acc, pause -> acc + pause.overlap(start, end) }

    /** Active time between two instants of the run: wall time minus the pauses inside it (never negative). */
    fun activeBetween(pauses: List<Pause>, start: Instant, end: Instant): Duration =
        if (end <= start) Duration.ZERO else (end - start) - pausedWithin(pauses, start, end)

    /** Is `t` inside a pause? */
    fun isPaused(pauses: List<Pause>, t: Instant): Boolean = pauses.any { t >= it.from && t < it.until }
}
