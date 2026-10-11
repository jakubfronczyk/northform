package com.jakubfronczyk.northform.ui.crash

import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.engine.run.CrashChoice
import com.jakubfronczyk.northform.engine.run.CrashFacts
import com.jakubfronczyk.northform.engine.run.CrashPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CrashPromptScreenTest {
    private val savedUntil = Instant.parse("2026-10-10T06:00:20Z")
    private val facts = CrashFacts(RecordingState.Active, startedAt = savedUntil - 30.minutes, tzOffset = 7200, distance = 5000.0, totalTime = 30.minutes, savedUntil = savedUntil)

    @Test
    fun the_buttons_keep_one_order_when_save_takes_the_lead_at_10_minutes() {
        val early = CrashPrompt.of(facts, savedUntil + 9.minutes)
        val late = CrashPrompt.of(facts, savedUntil + 11.minutes)
        assertEquals(CrashChoice.Continue, early.main)
        assertEquals(CrashChoice.Save, late.main)
        assertEquals(listOf(CrashChoice.Continue, CrashChoice.Save, CrashChoice.Delete), early.inFixedOrder())
        assertEquals(listOf(CrashChoice.Continue, CrashChoice.Save, CrashChoice.Delete), late.inFixedOrder(), "only the emphasis moves (D39)")
        assertEquals(listOf(CrashChoice.Save, CrashChoice.Delete), CrashPrompt.of(facts, savedUntil + 3.hours).inFixedOrder())
    }

    @Test
    fun the_prompt_redraws_at_the_next_minute_either_the_gap_or_the_wall_clock_turns() {
        // 06:03:50 (UTC+2 is whole minutes), gap 3:30: the wall minute turns in 10 s, the gap's in 30 s.
        assertEquals(10.seconds, untilNextMinute(facts, Instant.parse("2026-10-10T06:03:50Z")))
        // 06:03:10: the gap (2:50) turns in 10 s, the wall clock in 50 s.
        assertEquals(10.seconds, untilNextMinute(facts, Instant.parse("2026-10-10T06:03:10Z")))
        // Exactly on both boundaries: a full minute, never zero (no busy loop).
        assertEquals(60.seconds, untilNextMinute(facts.copy(savedUntil = Instant.parse("2026-10-10T06:00:00Z")), Instant.parse("2026-10-10T06:05:00Z")))
        // A clock moved backwards (the gap reads 0 until savedUntil + 1 min): the wall clock still turns.
        assertEquals(40.seconds, untilNextMinute(facts, Instant.parse("2026-10-10T05:59:20Z")))
    }
}
