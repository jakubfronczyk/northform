package com.jakubfronczyk.northform.ui.run

import androidx.compose.ui.graphics.Color
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.theme.Theme
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The cool-down's length in whole seconds (`RunReducer.cooldown`, 60): its ticks, its ring, its chart's width. */
private val cooldownSeconds = RunReducer.cooldown.inWholeSeconds.toInt()

/**
 * The heart rate since the stop as the Cool-down screen saw it (run-flow-artboards): one point per whole second after
 * the stop (`Cooldown.until` − `RunReducer.cooldown`), folded from the snapshots by [add]. A point is `hrNow`, or null
 * for a gap (the sensor lost, or a second the screen did not see). UI-side only: it lives as long as the screen, so a
 * rebuilt screen starts late with nulls before it and no [start]. The stored samples behind HR recovery are untouched.
 */
data class CooldownTrace(val points: List<Int?> = emptyList()) {
    /** The heart rate at the stop: the first point, null when it was not seen. */
    val start: Int? get() = points.firstOrNull()

    /**
     * [snapshot] seen at [now]. Outside `RunPhase.Cooldown` nothing is collected; a second already drawn keeps its first
     * reading; seconds skipped since the last point are gaps.
     */
    fun add(snapshot: RunSnapshot, now: Instant): CooldownTrace {
        val phase = snapshot.phase as? RunPhase.Cooldown ?: return this
        val tick = (now - (phase.until - RunReducer.cooldown)).inWholeSeconds.toInt().coerceIn(0, cooldownSeconds - 1)
        if (tick < points.size) return this
        val hr = if (snapshot.hrLost) null else snapshot.hrNow
        return CooldownTrace(points + List(tick - points.size) { null } + hr)
    }
}

/**
 * The Cool-down artboard (`Cooldown.dc.html`, run-flow-artboards; `RunView.swift:70` was the bare text) as one pure
 * function of the snapshot at `RunPhase.Cooldown` (its `until`, `hrNow`, `hrLost`, the totals frozen at stop), the run's
 * clock and the screen's [CooldownTrace]. The screen only lays it out; table-tested in `CooldownModelTest`.
 */
data class CooldownModel(
    /** "Run stopped" / "Walk stopped", in the type's colour. */
    val chipLabel: String,
    val chipColor: Color,
    /** "52:40 · 10.21 km": the time and distance frozen at the stop. */
    val totals: String,
    val title: String,
    val subtitle: String,
    /** "0:38": the time left, rounded up, never below 0:00. */
    val left: String,
    /** The ring's lit share: the time gone out of the minute (the artboard's dashoffset = c · remaining / 60). */
    val ring: Float,
    /** "Now": the live reading, [NO_VALUE] while the sensor is lost: never a stale value. */
    val hrNow: String,
    /** "Started at": the trace's first point, [NO_VALUE] when the trace did not see the stop. */
    val startedAt: String,
    /** "Drop so far": start − now as "−21" (a rise as "+3"), [NO_VALUE] without both. */
    val drop: String,
    /** The sensor is lost: Now and the chart's end in the warn colour, [lostLine] instead of [caption]. */
    val lost: Boolean,
    /** "Heart rate since stop · 0:22 of 1:00"; null while [lost]. */
    val caption: String?,
    val lostLine: String?,
    /** The trace placed on the chart, a gap as null; empty of points when the trace has no start to scale from. */
    val chart: List<ChartPoint?>,
) {
    /** [x]: the second over the minute, 0..1. [y]: 0 on the start's level, 1 at [CHART_SPAN_BPM] below it. */
    data class ChartPoint(val x: Float, val y: Float)

    companion object {
        /** The chart's height in bpm under the start: a strong one-minute recovery (~40 bpm) reaches the floor. */
        const val CHART_SPAN_BPM = 40

        fun from(snapshot: RunSnapshot, now: Instant, trace: CooldownTrace): CooldownModel {
            val until = (snapshot.phase as? RunPhase.Cooldown)?.until ?: now
            val leftSeconds = ceil((until - now).inSecondsDouble).toInt().coerceIn(0, cooldownSeconds)
            val gone = cooldownSeconds - leftSeconds
            val lost = snapshot.hrLost
            val hrNow = if (lost) null else snapshot.hrNow
            val start = trace.start
            return CooldownModel(
                chipLabel = "${SummaryModel.title(snapshot.type)} stopped",
                chipColor = Theme.of(snapshot.type),
                totals = "${RunFormat.time(snapshot.totalTime)} · ${RunFormat.distance(snapshot.distance)} km",
                title = "Cool-down",
                subtitle = "Stand or walk slowly. Measuring how fast your heart calms down.",
                left = RunFormat.time(leftSeconds.seconds),
                ring = gone.toFloat() / cooldownSeconds,
                hrNow = hrNow?.toString() ?: NO_VALUE,
                startedAt = start?.toString() ?: NO_VALUE,
                drop = if (start == null || hrNow == null) NO_VALUE else drop(start - hrNow),
                lost = lost,
                caption = if (lost) null else "Heart rate since stop · ${RunFormat.time(gone.seconds)} of ${RunFormat.time(RunReducer.cooldown)}",
                lostLine = if (lost) "Sensor lost · recovery can't be measured" else null,
                chart = trace.points.mapIndexed { i, hr ->
                    if (start == null || hr == null) null
                    else ChartPoint(i.toFloat() / cooldownSeconds, ((start - hr).toFloat() / CHART_SPAN_BPM).coerceIn(0f, 1f))
                },
            )
        }

        /** "−21" with the artboard's minus sign; "0"; a rise as "+3". */
        private fun drop(bpm: Int) = when {
            bpm > 0 -> "−$bpm"
            bpm < 0 -> "+${-bpm}"
            else -> "0"
        }
    }
}
