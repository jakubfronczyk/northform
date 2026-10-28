package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.metrics.Zone
import kotlin.math.roundToInt

/**
 * The zone editor (`ZoneEditor.dc.html`, profile-settings/04, SU2) as a pure value: the lower bounds of Z1–Z5 in whole %
 * of heart-rate reserve (Karvonen), moved one point at a time. Z1 never below [FLOOR], Z5 never above [CEILING], each at
 * least [GAP] points above the one below, so the zones can't overlap; a stepper at its limit is off. Stored as %, so a new
 * max or resting heart rate moves the bpm, never the percentages.
 */
data class ZoneEditor(private val bounds: List<Int>, private val profile: Profile) {
    /** One zone's row: "Z3 Moderate", from 70 % = 149 bpm, and whether each stepper is on. */
    data class Row(val label: String, val percent: Int, val bpm: Int, val canLower: Boolean, val canRaise: Boolean)

    val rows: List<Row> get() = bounds.indices.map { i ->
        val p = bounds[i]
        Row(
            label = "Z${i + 1} ${Zone.names[i + 1]}", percent = p,
            bpm = profile.bpmAt(p / 100.0),
            canLower = p - 1 >= min(i), canRaise = p + 1 <= max(i),
        )
    }

    /** "Preview · max 187 · resting 60 bpm": what the bpm are computed for. */
    val preview: String get() = "Preview · max ${profile.hrMax} · resting ${profile.hrRest} bpm"

    /**
     * The preview bar's six segment weights, Z0 first: Z0 drawn ten points wide, each zone as wide as its range up to
     * 100 %, at least 1 so none vanishes (`ZoneEditor.dc.html`).
     */
    val bar: List<Int> get() = (listOf(bounds.first() - 10) + bounds + 100).zipWithNext { a, b -> maxOf(1, b - a) }

    /** What Save stores. */
    val zones: HRZones get() = HRZones(bounds.map { it / 100.0 })

    fun lower(i: Int) = if (rows[i].canLower) copy(bounds = bounds.toMutableList().also { it[i]-- }) else this
    fun raise(i: Int) = if (rows[i].canRaise) copy(bounds = bounds.toMutableList().also { it[i]++ }) else this
    fun reset() = copy(bounds = DEFAULT)

    private fun min(i: Int) = if (i == 0) FLOOR else bounds[i - 1] + GAP
    private fun max(i: Int) = if (i == bounds.lastIndex) CEILING else bounds[i + 1] - GAP

    companion object {
        const val FLOOR = 30
        const val CEILING = 98
        const val GAP = 2
        val DEFAULT = listOf(50, 60, 70, 80, 90)

        /**
         * The editor for [profile]'s zones in whole %. Bounds the rules can't hold once rounded (closer than [GAP], outside
         * [FLOOR]..[CEILING]) open on [DEFAULT]: editing them could only produce zones that don't rise.
         */
        fun of(profile: Profile): ZoneEditor {
            val whole = profile.zones.lowerBounds.map { (it * 100).roundToInt() }
            val holds = whole.first() >= FLOOR && whole.last() <= CEILING && whole.zipWithNext().all { (a, b) -> b - a >= GAP }
            return ZoneEditor(if (holds) whole else DEFAULT, profile)
        }
    }
}
