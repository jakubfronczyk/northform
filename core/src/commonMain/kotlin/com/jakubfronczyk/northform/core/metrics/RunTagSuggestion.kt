package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * R0 / D76 — the run tag suggested from the recording (spec 08 "Run tag suggestion", rules ◻),
 * first match wins: intervals · tempo · long · easy. A walk has no tag (D15). The user can
 * change it on the summary; this only proposes.
 *
 * Intervals, made precise: the held HR intervals ([HrStream]) are classed *high* (≥ Z4), *low*
 * (≤ Z2) or *mid* (Z3). Mid is neutral — it neither extends nor breaks a stretch, so a swing may
 * pass through Z3 on its way down and up. A stretch of one class counts when it held for at
 * least [swingHold]; an HR gap ends it. A swing is a counting high followed by a counting low
 * followed by a counting high; three swings need four counting highs around three lows.
 */
object RunTagSuggestion {
    /** ◻ Each leg of a swing must hold this long. */
    val swingHold: Duration = 30.seconds
    const val swings = 3
    /** ◻ Tempo: this long in Z3–Z4. */
    val tempoTime: Duration = 20.minutes
    val tempoZones = 3..4
    /** ◻ Long: by total time or by distance. */
    val longTime: Duration = 75.minutes
    const val longDistance = 15_000.0
    const val highZone = 4
    const val lowZone = 2

    /**
     * @param samples the recording's heart-rate samples up to the stop (cool-down excluded), in time order.
     * @param totalTime stop − start, pauses included (spec 08 "Total time").
     * @param distance metres counted (the live distance).
     */
    fun suggest(type: RecordingType, samples: List<HrSample>, profile: Profile, totalTime: Duration, distance: Double): RunTag? =
        suggest(type, samples, profile, totalTime, distance) { TimeInZone.compute(samples, profile) }

    /** [suggest] with H3 of [samples] already computed by the caller (the summary shows it too). */
    fun suggest(
        type: RecordingType, samples: List<HrSample>, profile: Profile, totalTime: Duration, distance: Double,
        timeInZone: Map<Zone, Duration>,
    ): RunTag? = suggest(type, samples, profile, totalTime, distance) { timeInZone }

    /** H3 is asked for lazily: only a run that is not intervals reads it. */
    private inline fun suggest(
        type: RecordingType, samples: List<HrSample>, profile: Profile, totalTime: Duration, distance: Double,
        timeInZone: () -> Map<Zone, Duration>,
    ): RunTag? = when (type) {
        RecordingType.Walk -> null
        RecordingType.Run -> when {
            countSwings(samples, profile) >= swings -> RunTag.Intervals
            timeInTempoZones(timeInZone()) >= tempoTime -> RunTag.Tempo
            totalTime >= longTime || distance >= longDistance -> RunTag.Long
            else -> RunTag.Easy
        }
    }

    fun timeInTempoZones(samples: List<HrSample>, profile: Profile): Duration = timeInTempoZones(TimeInZone.compute(samples, profile))

    private fun timeInTempoZones(timeInZone: Map<Zone, Duration>): Duration =
        timeInZone.filterKeys { it.number in tempoZones }.values.fold(Duration.ZERO) { a, b -> a + b }

    private enum class Leg { High, Low }

    /** High → Low → High transitions over the counting stretches. */
    fun countSwings(samples: List<HrSample>, profile: Profile, hold: Duration = swingHold): Int {
        val legs = legs(samples, profile, hold)
        // A swing is Low with a High on both sides: count the Lows strictly inside the alternating list.
        var count = 0
        for (i in 1 until legs.size - 1) if (legs[i] == Leg.Low && legs[i - 1] == Leg.High && legs[i + 1] == Leg.High) count++
        return count
    }

    /** The counting stretches in order; consecutive equal legs (split by Z3 or a gap, each long enough) are one. */
    private fun legs(samples: List<HrSample>, profile: Profile, hold: Duration): List<Leg> {
        val legs = ArrayList<Leg>()
        var current: Leg? = null
        var held = Duration.ZERO
        var lastEnd: kotlin.time.Instant? = null
        fun close() {
            if (current != null && held >= hold && legs.lastOrNull() != current) legs += current!!
            current = null
            held = Duration.ZERO
        }
        for (interval in HrStream.intervals(samples)) {
            if (lastEnd != null && interval.from != lastEnd) close() // an HR gap ends the stretch
            lastEnd = interval.until
            val zone = profile.zoneOf(interval.bpm).number
            val leg = when {
                zone >= highZone -> Leg.High
                zone <= lowZone -> Leg.Low
                else -> null // Z3: neutral
            }
            if (leg == null) continue
            if (leg != current) {
                close()
                current = leg
            }
            held += interval.duration
        }
        close()
        return legs
    }
}
