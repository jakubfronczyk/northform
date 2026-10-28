package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.KeepAlive
import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.adapters.location.LocationSink
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** D101 on a virtual clock: the forwarder ends by itself, Kotlin restarts it with 2 · 4 · 8 · 16 · 30 s. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class LiveLocationRestartTest {
    private val start = Instant.parse("2026-10-08T10:00:00Z")

    /** One timeline for the forwarder and the keep-alive, so a test can assert their order. */
    private val trace = ArrayList<String>()

    /** The keep-alive as a fake: writes `hold`/`release` into [trace]. */
    private val keepAlive = object : KeepAlive {
        override fun hold() { trace += "hold" }
        override fun release() { trace += "release" }
    }

    /**
     * The Swift forwarder as a fake: counts starts, lets the test end the stream or deliver a fix. Like the real one
     * (`LocationForwarder.swift`), `start` stops the previous stream first, and stopping a stream that is still alive
     * ends its `for try await`, which reports `onEnded` to *that* stream's sink.
     */
    private inner class FakeUpdates : LocationUpdates {
        var starts = 0
        var stops = 0
        var sink: LocationSink? = null
        private var alive = false
        override fun start(sink: LocationSink) { cancelLive(); starts += 1; this.sink = sink; alive = true; trace += "start" }
        override fun stop() { stops += 1; trace += "stop"; cancelLive() }
        private fun cancelLive() { if (alive) { alive = false; sink!!.onEnded("cancelled") } }
        fun end() { alive = false; sink!!.onEnded("test") }
        fun fix(at: Instant) {
            val location = CLLocation(
                coordinate = CLLocationCoordinate2DMake(52.5, 13.4), altitude = 0.0, horizontalAccuracy = 5.0, verticalAccuracy = -1.0,
                timestamp = NSDate.dateWithTimeIntervalSince1970(at.epochSeconds.toDouble()),
            )
            sink!!.onUpdate(location, stationary = false)
        }
    }

    @Test
    fun restarts_with_the_backoff_reset_by_a_fix_and_stops_with_the_collector() = runTest {
        val updates = FakeUpdates()
        val log = ArrayList<String>()
        val received = ArrayList<LocationFix>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse), keepAlive, log::add)
        val job = live.fixes().onEach { received += it }.launchIn(backgroundScope)
        runCurrent()
        assertEquals(1, updates.starts)

        updates.end(); advanceTimeBy(1.seconds); runCurrent()
        assertEquals(1, updates.starts, "not before 2 s")
        advanceTimeBy(1.seconds); runCurrent()
        assertEquals(2, updates.starts, "restart 1 after 2 s")

        updates.end(); advanceTimeBy(4.seconds); runCurrent()
        assertEquals(3, updates.starts, "restart 2 after 4 s")

        updates.fix(start + 10.seconds); runCurrent() // a fix resets the backoff
        assertEquals(1, received.size)
        updates.end(); advanceTimeBy(2.seconds); runCurrent()
        assertEquals(4, updates.starts, "back to 2 s after a fix")

        job.cancel(); runCurrent()
        updates.end(); advanceTimeBy(60.seconds); runCurrent()
        assertEquals(4, updates.starts, "no restart once the collector is gone")
        assertEquals(1, updates.stops)
        assertEquals(listOf("live updates ended: test", "restart 1 in 2 s", "live updates ended: test", "restart 2 in 4 s", "live updates ended: test", "restart 1 in 2 s"), log.take(6))
    }

    @Test
    fun caps_the_backoff_at_30_seconds() = runTest {
        val updates = FakeUpdates()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse), keepAlive = keepAlive)
        live.fixes().launchIn(backgroundScope); runCurrent()
        for (wait in listOf(2, 4, 8, 16, 30, 30, 30)) {
            val before = updates.starts
            updates.end(); advanceTimeBy((wait - 1).seconds); runCurrent()
            assertEquals(before, updates.starts, "not before $wait s")
            advanceTimeBy(1.seconds); runCurrent()
            assertEquals(before + 1, updates.starts, "restart after $wait s")
        }
    }

    @Test
    fun does_not_restart_when_location_is_denied() = runTest {
        val updates = FakeUpdates()
        val log = ArrayList<String>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.Denied), keepAlive, log::add)
        live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); advanceTimeBy(120.seconds); runCurrent()
        assertEquals(1, updates.starts)
        assertEquals("location denied: no restart", log.last())
    }

    // ── adapters/03: denied, restricted and re-granted mid-run ───────────────────────────────

    @Test
    fun does_not_restart_when_location_is_restricted() = runTest {
        val updates = FakeUpdates()
        val log = ArrayList<String>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.Restricted), keepAlive, log::add)
        live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); advanceTimeBy(120.seconds); runCurrent()
        assertEquals(1, updates.starts)
        assertEquals("location restricted: no restart", log.last())
    }

    @Test
    fun a_grant_while_denied_mid_run_restarts_gps_at_once_with_the_backoff_reset() = runTest {
        val updates = FakeUpdates()
        val log = ArrayList<String>()
        val authorization = MutableStateFlow(LocationAuthorization.Denied)
        val live = LiveLocation(updates, virtualWallClock(start), authorization, keepAlive, log::add)
        live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); advanceTimeBy(120.seconds); runCurrent()
        assertEquals(1, updates.starts, "denied: GPS stays off")

        authorization.value = LocationAuthorization.WhenInUse; runCurrent()
        assertEquals(2, updates.starts, "granted in Settings: restarted without waiting for a backoff step")
        assertEquals("location granted: restart now", log.last())

        updates.end(); advanceTimeBy(2.seconds); runCurrent()
        assertEquals(3, updates.starts, "the backoff starts from 2 s again after the grant")
        authorization.value = LocationAuthorization.WhenInUse; runCurrent()
        assertEquals(3, updates.starts, "the same value again is not a change (StateFlow conflates it)")
    }

    @Test
    fun a_grant_cancels_a_pending_backoff_and_a_grant_after_the_collector_left_does_nothing() = runTest {
        val updates = FakeUpdates()
        val authorization = MutableStateFlow(LocationAuthorization.NotDetermined)
        val live = LiveLocation(updates, virtualWallClock(start), authorization, keepAlive = keepAlive)
        val job = live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); updates.end(); // attempt 2: a 4 s wait is pending (not-determined still restarts, as before)
        advanceTimeBy(2.seconds); runCurrent()
        val before = updates.starts
        authorization.value = LocationAuthorization.WhenInUse; runCurrent()
        assertEquals(before + 1, updates.starts, "granted: restarted now")
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(before + 1, updates.starts, "the pending backoff restart was cancelled, not doubled")

        job.cancel(); runCurrent()
        authorization.value = LocationAuthorization.Denied; authorization.value = LocationAuthorization.WhenInUse; runCurrent()
        assertEquals(before + 1, updates.starts, "nobody listens: no restart")
    }

    // ── adapters/05: the adapter owns the keep-alive ─────────────────────────────────────────

    @Test
    fun holds_the_keep_alive_before_gps_starts_and_releases_it_after_gps_stops_when_the_collector_leaves() = runTest {
        val updates = FakeUpdates()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse), keepAlive = keepAlive)
        assertEquals(emptyList(), trace, "nothing is held before anyone collects")
        val job = live.fixes().launchIn(backgroundScope); runCurrent()
        assertEquals(listOf("hold", "start"), trace, "the session is held before the first fix can arrive")

        updates.end(); advanceTimeBy(2.seconds); runCurrent()
        assertEquals(listOf("hold", "start", "start"), trace, "a D101 restart keeps the one hold")

        job.cancel(); runCurrent()
        assertEquals(listOf("hold", "start", "start", "stop", "release"), trace, "GPS stops, then the keep-alive goes, exactly once")
    }

    @Test
    fun denied_still_holds_once_and_releases_with_the_collector() = runTest {
        val updates = FakeUpdates()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.Denied), keepAlive = keepAlive)
        val job = live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); advanceTimeBy(60.seconds); runCurrent()
        job.cancel(); runCurrent()
        assertEquals(listOf("hold", "start", "stop", "release"), trace, "as before: the run holds it whatever the answer; iOS keeps it suspended")
    }

    // ── review fixes ─────────────────────────────────────────────────────────────────────────

    @Test
    fun the_old_stream_ending_because_a_grant_replaced_it_does_not_restart_gps() = runTest {
        val updates = FakeUpdates()
        val authorization = MutableStateFlow(LocationAuthorization.NotDetermined)
        val live = LiveLocation(updates, virtualWallClock(start), authorization, keepAlive = keepAlive)
        live.fixes().launchIn(backgroundScope); runCurrent()
        authorization.value = LocationAuthorization.WhenInUse; runCurrent()
        assertEquals(2, updates.starts, "granted: restarted once")
        advanceTimeBy(120.seconds); runCurrent()
        assertEquals(2, updates.starts, "the replaced stream's onEnded is stale: no backoff restart loop")
    }

    @Test
    fun a_slow_collector_loses_no_fix() = runTest {
        val updates = FakeUpdates()
        val received = ArrayList<LocationFix>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse), keepAlive = keepAlive)
        live.fixes().onEach { delay(1.seconds); received += it }.launchIn(backgroundScope); runCurrent()
        repeat(200) { updates.fix(start + (it + 1).seconds) }
        advanceTimeBy(300.seconds); runCurrent()
        assertEquals(200, received.size)
    }

    @Test
    fun overlapping_collections_keep_gps_and_the_keep_alive_until_the_last_one_leaves() = runTest {
        val updates = FakeUpdates()
        val received = ArrayList<LocationFix>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse), keepAlive = keepAlive)
        val finishing = live.fixes().launchIn(backgroundScope); runCurrent()
        val next = live.fixes().onEach { received += it }.launchIn(backgroundScope); runCurrent()
        finishing.cancel(); advanceTimeBy(60.seconds); runCurrent()
        assertEquals(listOf("hold", "start", "start"), trace, "the finishing run neither stops the new run's GPS, nor releases, nor restarts")

        updates.fix(start + 61.seconds); runCurrent()
        assertEquals(1, received.size, "the new run still gets fixes")
        next.cancel(); runCurrent()
        assertEquals(listOf("hold", "start", "start", "stop", "release"), trace)
    }
}
