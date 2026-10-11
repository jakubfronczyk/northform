package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.adapters.location.LocationSink
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayInput
import com.jakubfronczyk.northform.replay.ReplayMeta
import com.jakubfronczyk.northform.replay.ReplayScript
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * adapters/04 — `CLLocation` → [LocationFix] carries the four accuracies the Swift build has recorded
 * since run 3 (`CoreLocationSource.swift:100`): a negative Core Location value means "invalid" and
 * becomes null, so the fixture line omits it. End to end: Swift hands the raw `CLLocation` over the
 * D10 seam, Kotlin maps it, the frozen v1 fixture codec writes `sAcc`/`course`/`cAcc`/`vAcc`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class LiveLocationMappingTest {
    private val start = Instant.parse("2026-10-08T10:00:00Z")
    private val meta = ReplayMeta(RecordingType.Run, start, 187, 60, Sex.Male)

    private class FakeUpdates : LocationUpdates {
        var sink: LocationSink? = null
        override fun start(sink: LocationSink) { this.sink = sink }
        override fun stop() {}
    }

    /** The 9-argument `CLLocation` initializer (iOS 13.4+), reached through the Kotlin/Native Obj-C interop. */
    private fun location(at: Instant, verticalAccuracy: Double, course: Double, courseAccuracy: Double, speed: Double, speedAccuracy: Double) =
        CLLocation(
            coordinate = CLLocationCoordinate2DMake(52.5, 13.4), altitude = 40.0, horizontalAccuracy = 5.0, verticalAccuracy = verticalAccuracy,
            course = course, courseAccuracy = courseAccuracy, speed = speed, speedAccuracy = speedAccuracy,
            timestamp = NSDate.dateWithTimeIntervalSince1970(at.epochSeconds.toDouble()),
        )

    /** One update through the real adapter: the fix as the engine would receive it. */
    private fun TestScope.fixFrom(location: CLLocation, stationary: Boolean): LocationFix {
        val updates = FakeUpdates()
        val received = ArrayList<LocationFix>()
        val live = LiveLocation(updates, virtualWallClock(start), MutableStateFlow(LocationAuthorization.WhenInUse))
        live.fixes().onEach { received += it }.launchIn(backgroundScope)
        runCurrent()
        updates.sink!!.onUpdate(location, stationary)
        runCurrent()
        return received.single()
    }

    @Test
    fun maps_speed_accuracy_course_and_vertical_accuracy_into_the_fix_and_the_fixture_line() = runTest {
        val fix = fixFrom(location(start + 1.seconds, verticalAccuracy = 3.5, course = 271.5, courseAccuracy = 12.0, speed = 2.8, speedAccuracy = 0.4), stationary = false)
        assertEquals(0.4, fix.speedAccuracy)
        assertEquals(271.5, fix.course)
        assertEquals(12.0, fix.courseAccuracy)
        assertEquals(3.5, fix.verticalAccuracy)
        assertEquals(40.0, fix.altitude)

        val jsonl = Fixture.encode(ReplayScript(meta, listOf(ReplayInput.Fix(fix))))
        assertTrue(jsonl.contains(""""sAcc":0.4""") && jsonl.contains(""""course":271.5""") && jsonl.contains(""""cAcc":12""") && jsonl.contains(""""vAcc":3.5"""), jsonl)
        assertEquals(fix, (Fixture.parse(jsonl).inputs.single() as ReplayInput.Fix).fix)
    }

    @Test
    fun negative_core_location_values_are_invalid_and_left_out() = runTest {
        val fix = fixFrom(location(start + 1.seconds, verticalAccuracy = -1.0, course = -1.0, courseAccuracy = -1.0, speed = -1.0, speedAccuracy = -1.0), stationary = true)
        assertNull(fix.speedAccuracy); assertNull(fix.course); assertNull(fix.courseAccuracy); assertNull(fix.verticalAccuracy)
        assertNull(fix.speed); assertNull(fix.altitude)
        val jsonl = Fixture.encode(ReplayScript(meta, listOf(ReplayInput.Fix(fix))))
        for (key in listOf("sAcc", "course", "cAcc", "vAcc", "alt", "speed")) assertFalse(jsonl.contains("\"$key\""), "$key absent: $jsonl")
    }
}
