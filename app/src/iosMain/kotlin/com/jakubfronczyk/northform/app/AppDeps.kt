package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.adapters.location.KeepAlive
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.replay.ReplayScript
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import kotlin.time.Instant

/**
 * Where a run's samples come from (`AppDeps.swift:14-16`, `AppModel.swift:64-83`): the real sensor and
 * GPS on the phone, a bundled fixture in the simulator. Chosen once at boot; illegal mixes can't be built.
 */
sealed interface RunSource {
    class Live(
        val sensor: HeartRateSensor,
        val location: LocationProvider,
        /** Keeps the process alive while locked during a run (D14). */
        val keepAlive: KeepAlive,
        /** Asks for the permissions a live run needs before the run screen opens (`AppModel.swift:64-74`). */
        val requestPermissions: suspend () -> Unit,
    ) : RunSource

    class Replay(val script: () -> ReplayScript) : RunSource
}

/**
 * How the app is wired (`App/Sources/AppDeps.swift`): the run source, the one store, the wall clock,
 * alerts and the sample profile. Built once in `boot()`. Walking-skeleton Milestone A: the store is in memory.
 */
class AppDeps(
    val source: RunSource,
    val store: RecordingStore,
    val wallClock: WallClock,
    val alerts: AlertPlayer,
    /** Removes this run's lock-screen notifications once it closes (`AppModel.swift:131`). */
    val clearAlerts: () -> Unit = {},
    val profile: Profile = sampleProfile,
    val newRecordingId: () -> String = { NSUUID().UUIDString },
    val tzOffset: () -> Int = { NSTimeZone.localTimeZone.secondsFromGMT.toInt() },
) {
    companion object {
        /** The iPhone simulator has neither Bluetooth nor GPS; the Swift build used `#if targetEnvironment(simulator)`. */
        val isSimulator: Boolean = NSProcessInfo.processInfo.environment.containsKey("SIMULATOR_DEVICE_NAME")

        /** Sample profile from the style guide until Setup exists (`AppDeps.swift:45`). */
        val sampleProfile = Profile(
            birthDate = Instant.parse("1996-03-14T00:00:00Z"), sex = Sex.Male,
            weightKg = 78.0, heightCm = 182.0, hrMax = 187, hrRest = 60,
        )
    }
}
