package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.LocationSink
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * D9 enforcement, runs on `iosSimulatorArm64Test` (`just test-sim`). The simulator has no Bluetooth
 * radio, but constructing a `CBCentralManager` is legal there (state = unsupported), so "the central
 * exists when boot() returns" is checkable off-device. The ORDER proof (willRestoreState after boot)
 * is spike step 4b on the phone.
 */
class BootTest {
    private object NoLocation : LocationUpdates {
        override fun start(sink: LocationSink) {}
        override fun stop() {}
    }

    @Test
    fun central_manager_and_sensor_mailbox_exist_when_boot_returns() {
        val t = TimeSource.Monotonic.markNow()
        NorthformApp.boot(NoLocation)
        val took = t.elapsedNow()
        assertTrue(NorthformApp.isBooted)
        // lateinit access throws if boot() skipped step 2 or 3
        NorthformApp.central
        NorthformApp.sensor
        // TODO(spike): tighten once measured on the 16e; the simulator number is only a smoke check.
        assertTrue(took.inWholeMilliseconds < 2_000, "boot() took $took")
    }

    @Test
    fun boot_is_idempotent() {
        NorthformApp.boot(NoLocation)
        val first = NorthformApp.central
        NorthformApp.boot(NoLocation)
        assertTrue(first === NorthformApp.central)
    }
}
