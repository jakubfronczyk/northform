package com.jakubfronczyk.northform.engine.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** D11: ≤ 1 update per 5 s, immediate on a phase change, one Start, one End, nothing after. */
class LiveActivityFeedTest {
    private val t0 = Instant.fromEpochSeconds(1_800_000_000)
    private fun active(sec: Int, distance: Double = sec * 3.0) =
        RunSnapshot(RunPhase.Active, startedAt = t0, totalTime = sec.seconds, distance = distance)

    @Test
    fun nothing_at_the_gate_or_countdown_then_start_on_the_first_content() {
        val feed = LiveActivityFeed()
        assertNull(feed.on(RunSnapshot(RunPhase.Gate), t0))
        assertNull(feed.on(RunSnapshot(RunPhase.Countdown(endsAt = t0 + 3.seconds)), t0 + 1.seconds))
        assertEquals(LiveActivityEvent.Start(LiveActivityContent.of(active(0))!!), feed.on(active(0), t0))
    }

    @Test
    fun updates_are_held_to_one_per_5_s() {
        val feed = LiveActivityFeed()
        feed.on(active(0), t0)
        assertNull(feed.on(active(1), t0 + 1.seconds), "1 s after Start: held")
        assertNull(feed.on(active(4), t0 + 4.seconds), "4 s: held")
        assertIs<LiveActivityEvent.Update>(feed.on(active(5), t0 + 5.seconds), "5 s: through")
        assertNull(feed.on(active(9), t0 + 9.seconds), "4 s after the last update: held")
        assertIs<LiveActivityEvent.Update>(feed.on(active(10), t0 + 10.seconds))
    }

    @Test
    fun a_phase_change_goes_through_at_once_and_resets_the_interval() {
        val feed = LiveActivityFeed()
        feed.on(active(0), t0)
        val paused = RunSnapshot(RunPhase.Paused(since = t0 + 1.seconds), startedAt = t0, totalTime = 1.seconds)
        val event = feed.on(paused, t0 + 1.seconds)
        assertEquals(LiveActivityPhase.Paused, assertIs<LiveActivityEvent.Update>(event).content.phase)
        assertNull(feed.on(paused.copy(totalTime = 3.seconds), t0 + 3.seconds), "2 s after the pause update: held")
        assertIs<LiveActivityEvent.Update>(feed.on(active(4), t0 + 4.seconds), "resume: a phase change, through")
    }

    @Test
    fun the_cooldown_ends_the_activity_once_and_nothing_follows() {
        val feed = LiveActivityFeed()
        feed.on(active(0), t0)
        val cooldown = RunSnapshot(RunPhase.Cooldown(until = t0 + 70.seconds), startedAt = t0, totalTime = 60.seconds, distance = 180.0)
        val end = assertIs<LiveActivityEvent.End>(feed.on(cooldown, t0 + 60.seconds))
        assertEquals(LiveActivityPhase.Done, end.content.phase)
        assertEquals(180.0, end.content.distanceM)
        assertNull(feed.on(RunSnapshot(RunPhase.Done, startedAt = t0, totalTime = 60.seconds), t0 + 70.seconds), "already ended")
        assertNull(feed.close(), "already ended")
    }

    @Test
    fun close_ends_a_started_run_with_what_it_last_showed_and_is_a_no_op_otherwise() {
        assertNull(LiveActivityFeed().close(), "never started: nothing to end")
        val feed = LiveActivityFeed()
        feed.on(active(0), t0)
        feed.on(active(5, distance = 20.0), t0 + 5.seconds)
        val end = feed.close()
        assertEquals(LiveActivityPhase.Done, end?.content?.phase)
        assertEquals(20.0, end?.content?.distanceM, "the last content, not a fresh one")
        assertNull(feed.close(), "once")
        assertNull(feed.on(active(10), t0 + 10.seconds), "nothing after End")
    }
}
