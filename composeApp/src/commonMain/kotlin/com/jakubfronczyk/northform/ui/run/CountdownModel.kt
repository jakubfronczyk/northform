package com.jakubfronczyk.northform.ui.run

import androidx.compose.ui.graphics.Color
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.ui.theme.Theme
import kotlin.math.ceil
import kotlin.time.Instant

/**
 * The Countdown artboard (`Countdown.dc.html`, run-flow-artboards) as one pure function of `RunPhase.Countdown.endsAt`,
 * the run's clock and the run's type: the seconds left, the ring and dots they drive, the chip and the copy. The screen
 * only lays it out; the rules are table-tested in `CountdownModelTest`.
 */
data class CountdownModel(
    /** Seconds left, rounded up and clamped to 1..[dots]: never 0 (`RunView.swift:56`), never above the countdown. */
    val seconds: Int,
    /** The ring's lit share, [seconds] / [dots]: full at 5, draining a fifth per second (the artboard's `dash`). */
    val ring: Float,
    /**
     * Dots lit, one per second gone: [dots] − [seconds], the artboard's own rule (`i <= 5 − n`). So 0 at 5 and 4 at 1:
     * the fifth would light at 0, which is never shown (the run is Active by then).
     */
    val litDots: Int,
    /** One dot per second of `RunReducer.countdown` (5). */
    val dots: Int,
    /** The chip: "Run" / "Walk", in the type's colour (accent / grey). */
    val typeLabel: String,
    val chipColor: Color,
    /** VoiceOver's reading of the timer: "Run starts in 3 seconds". */
    val accessibility: String,
    val headline: String,
    val hint: String,
) {
    /** The big number. */
    val number: String get() = "$seconds"

    companion object {
        private val total = RunReducer.countdown.inWholeSeconds.toInt()

        fun from(endsAt: Instant, now: Instant, type: RecordingType): CountdownModel {
            val seconds = ceil((endsAt - now).inSecondsDouble).toInt().coerceIn(1, total)
            val label = SummaryModel.title(type)
            return CountdownModel(
                seconds = seconds,
                ring = seconds.toFloat() / total,
                litDots = total - seconds,
                dots = total,
                typeLabel = label,
                chipColor = Theme.of(type),
                accessibility = "$label starts in $seconds seconds",
                headline = "Put your phone in your pocket",
                hint = "A buzz every second",
            )
        }
    }
}
