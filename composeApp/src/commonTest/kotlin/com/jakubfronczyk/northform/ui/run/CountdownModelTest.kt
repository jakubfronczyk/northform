package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.ui.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** run-flow-artboards: the Countdown artboard (`Countdown.dc.html`) over `RunPhase.Countdown.endsAt` and the run's clock. */
class CountdownModelTest {

    private val endsAt = Instant.fromEpochSeconds(1_700_000_005)

    private fun at(left: kotlin.time.Duration, type: RecordingType = RecordingType.Run) = CountdownModel.from(endsAt, endsAt - left, type)

    @Test
    fun the_seconds_round_up_and_stay_within_one_to_five() {
        // (time left, shown): a part second counts as a whole one; never 0, never more than the countdown.
        val table = listOf(
            5.seconds to 5, 4.9.seconds to 5, 4.seconds to 4, 3.2.seconds to 4, 3.seconds to 3,
            1.seconds to 1, 100.milliseconds to 1, 0.seconds to 1, (-2).seconds to 1, 9.seconds to 5,
        )
        for ((left, shown) in table) assertEquals(shown, at(left).seconds, "at $left left")
    }

    @Test
    fun the_ring_drains_and_the_dots_light_one_per_second_gone() {
        // (seconds shown, ring fraction, lit dots): the artboard's dash = c·n/5 and dots i ≤ 5 − n.
        val table = listOf(Triple(5, 1f, 0), Triple(4, 0.8f, 1), Triple(3, 0.6f, 2), Triple(2, 0.4f, 3), Triple(1, 0.2f, 4))
        for ((n, ring, lit) in table) {
            val m = at(n.seconds)
            assertEquals(ring, m.ring, 0.0001f, "ring at $n")
            assertEquals(lit, m.litDots, "lit dots at $n")
            assertEquals(5, m.dots)
        }
    }

    @Test
    fun the_chip_follows_the_type() {
        val run = at(3.seconds)
        assertEquals("Run", run.typeLabel)
        assertEquals(Theme.accent, run.chipColor)
        assertEquals("Run starts in 3 seconds", run.accessibility)
        val walk = at(3.seconds, RecordingType.Walk)
        assertEquals("Walk", walk.typeLabel)
        assertEquals(Theme.text2, walk.chipColor)
        assertEquals("Walk starts in 3 seconds", walk.accessibility)
    }

    @Test
    fun the_copy_is_the_artboards() {
        val m = at(3.seconds)
        assertEquals("Put your phone in your pocket", m.headline)
        assertEquals("A buzz every second", m.hint)
        assertEquals("1", at(1.seconds).number)
    }
}
