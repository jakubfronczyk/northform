package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.adapters.location.LocationSink
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /** The Swift forwarder as a fake: counts starts, lets the test end the stream or deliver a fix. */
    private class FakeUpdates : LocationUpdates {
        var starts = 0
        var stops = 0
        var sink: LocationSink? = null
        override fun start(sink: LocationSink) { starts += 1; this.sink = sink }
        override fun stop() { stops += 1 }
        fun end() = sink!!.onEnded("test")
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
        val live = LiveLocation(updates, virtualWallClock(start), log::add) { LocationAuthorization.WhenInUse }
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
        val live = LiveLocation(updates, virtualWallClock(start), {}) { LocationAuthorization.WhenInUse }
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
        val live = LiveLocation(updates, virtualWallClock(start), log::add) { LocationAuthorization.Denied }
        live.fixes().launchIn(backgroundScope); runCurrent()
        updates.end(); advanceTimeBy(120.seconds); runCurrent()
        assertEquals(1, updates.starts)
        assertEquals("location denied: no restart", log.last())
    }
}
