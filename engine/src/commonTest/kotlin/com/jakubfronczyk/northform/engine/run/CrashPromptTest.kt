package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingState.Active
import com.jakubfronczyk.northform.core.RecordingState.Computing
import com.jakubfronczyk.northform.core.RecordingState.Stopping
import com.jakubfronczyk.northform.engine.run.CrashChoice.Continue
import com.jakubfronczyk.northform.engine.run.CrashChoice.Delete
import com.jakubfronczyk.northform.engine.run.CrashChoice.Save
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** R28's prompt rule (D30): which answers are offered and which one leads, by state and interruption. */
class CrashPromptTest {
    @Test
    fun continue_leads_under_10_min_trails_until_2_h_and_is_not_offered_from_2_h() {
        data class Row(val state: RecordingState, val gap: Duration, val choices: List<CrashChoice>, val main: CrashChoice)
        val table = listOf(
            Row(Active, 0.seconds, listOf(Continue, Save, Delete), Continue),
            Row(Active, 10.minutes - 1.seconds, listOf(Continue, Save, Delete), Continue), // 9:59
            Row(Active, 10.minutes, listOf(Save, Continue, Delete), Save), // 10:00, `CrashPromptLate`: Save leads
            Row(Active, 2.hours - 1.seconds, listOf(Save, Continue, Delete), Save), // 1:59:59
            Row(Active, 2.hours, listOf(Save, Delete), Save), // 2:00:00: an overnight "run" is not continued
            Row(Active, 9.hours, listOf(Save, Delete), Save),
            Row(Stopping, 1.minutes, listOf(Save, Delete), Save), // hold-to-stop happened: the run is over
            Row(Computing, 1.minutes, listOf(Save, Delete), Save), // every write was done
        )
        for (row in table) {
            val prompt = CrashPrompt.of(row.state, row.gap)
            assertEquals(row.choices, prompt.choices, "$row")
            assertEquals(row.main, prompt.main, "$row")
        }
        assertFailsWith<IllegalStateException> { CrashPrompt.of(RecordingState.Saved, 1.minutes) }
    }

    @Test
    fun the_interruption_is_measured_at_the_moment_asked_not_when_the_facts_were_read() {
        assertEquals(Continue, CrashPrompt.of(facts, now = savedUntil + 5.minutes).main)
        assertEquals(Save, CrashPrompt.of(facts, now = savedUntil + 10.minutes).main, "the same facts, later: Save leads")
        assertEquals(listOf(Save, Delete), CrashPrompt.of(facts, now = savedUntil + 2.hours).choices, "left up for 2 h: Continue is gone")
    }

    private val savedUntil = Instant.parse("2026-10-11T06:00:00Z")
    private val facts = CrashFacts(Active, startedAt = savedUntil - 30.minutes, tzOffset = 0, distance = 5000.0, totalTime = 30.minutes, savedUntil = savedUntil)

    @Test
    fun a_clock_moved_backwards_reads_as_no_interruption() {
        assertEquals(Duration.ZERO, facts.interruptedFor(savedUntil - 3.minutes))
    }

    @Test
    fun the_crash_mark_is_an_auto_crash_recovered_at_the_answer() {
        assertEquals(RecordingEvent(savedUntil, EventKind.CrashRecovered, EventSource.Auto), crashMark(savedUntil))
    }

    @Test
    fun resolve_answers_the_tap_with_what_the_app_does() {
        val now = savedUntil + 5.minutes
        assertEquals(CrashPrompt.Resolution.Continue(at = now), CrashPrompt.resolve(facts, now, Continue))
        assertEquals(CrashPrompt.Resolution.Save(mark = crashMark(now), endAt = savedUntil), CrashPrompt.resolve(facts, now, Save), "D30: ends at the heartbeat")
        assertEquals(CrashPrompt.Resolution.Delete, CrashPrompt.resolve(facts, now, Delete))
        val late = savedUntil + 2.hours
        assertEquals(CrashPrompt.Resolution.Refused(Continue, 2.hours), CrashPrompt.resolve(facts, late, Continue), "D30: a Continue drawn earlier is refused at the tap")
        assertEquals(CrashPrompt.Resolution.Save(mark = crashMark(late), endAt = savedUntil), CrashPrompt.resolve(facts, late, Save))
    }
}
