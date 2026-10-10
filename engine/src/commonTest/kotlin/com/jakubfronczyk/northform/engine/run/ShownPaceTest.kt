package com.jakubfronczyk.northform.engine.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D22 (Swift D122): the shown current pace steps in 5 s/km; the pace underneath is untouched. */
class ShownPaceTest {

    @Test
    fun rounds_to_the_nearest_five_seconds_per_km() {
        assertEquals(305.0, ShownPace.round(304.0))
        assertEquals(300.0, ShownPace.round(302.4))
        assertEquals(300.0, ShownPace.round(300.0))
        assertEquals(390.0, ShownPace.round(390.530))
        assertEquals(445.0, ShownPace.round(444.597))
    }

    @Test
    fun a_tie_goes_up_like_the_shown_seconds_do() {
        // `RunFormat.pace` rounds half away from zero; the step does the same so 5:02.5 never shows as 5:00.
        assertEquals(305.0, ShownPace.round(302.5))
    }

    @Test
    fun hidden_stays_hidden() {
        assertNull(ShownPace.round(null))
    }

    @Test
    fun rounding_is_only_for_the_display() {
        // A snapshot keeps the raw D106 pace: the stored pace, the summary and the Swift pins never see the step.
        val snapshot = RunSnapshot(phase = RunPhase.Active, currentPace = 304.0)
        assertEquals(304.0, snapshot.currentPace)
        assertEquals(305.0, ShownPace.round(snapshot.currentPace))
    }
}
