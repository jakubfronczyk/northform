package com.jakubfronczyk.northform.ui.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** run-flow-artboards review: the countdown and cool-down redraw once a second, when the shown seconds change. */
class SecondTickTest {

    private val end = Instant.fromEpochSeconds(1_700_000_060)

    @Test
    fun it_wakes_when_the_time_left_crosses_a_whole_second() {
        // (time left, wait): the shown seconds round up, so they change when the time left crosses n.000 s.
        val table = listOf(
            2.4.seconds to 400.milliseconds, 3.seconds to 1.seconds, 0.1.seconds to 100.milliseconds,
            59.999.seconds to 999.milliseconds, 0.seconds to 1.seconds, (-1.5).seconds to 1.seconds,
        )
        for ((left, wait) in table) assertEquals(wait, untilNextSecond(end, end - left), "at $left left")
    }
}
