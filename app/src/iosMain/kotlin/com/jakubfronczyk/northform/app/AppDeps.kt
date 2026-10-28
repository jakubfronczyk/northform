package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.ReliabilityStore
import com.jakubfronczyk.northform.core.ports.ProfileStore
import com.jakubfronczyk.northform.core.ports.SavedRecordings
import com.jakubfronczyk.northform.engine.store.InMemoryProfileStore
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.LiveActivityEvent
import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.replay.ReplayLocation
import com.jakubfronczyk.northform.replay.ReplayMeta
import com.jakubfronczyk.northform.replay.ReplayScript
import com.jakubfronczyk.northform.replay.ReplaySensor
import com.jakubfronczyk.northform.replay.ReplayTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import kotlin.time.Instant

/** Keeps the process alive while locked during a run (D14): `KeepAlive` on the phone, a fake in tests. */
interface RunKeepAlive {
    fun hold()
    fun release()
}

/**
 * What a source hands one run: its two input ports, a hook for the moment the session is running
 * (hold the keep-alive, start the playback) and the matching release when the run is over.
 */
class RunInputs(
    val sensor: HeartRateSensor,
    val location: LocationProvider,
    val onRunning: () -> Unit = {},
    val release: () -> Unit = {},
)

/**
 * Where a run's samples come from (`AppDeps.swift:14-16`, `AppModel.swift:64-83`): the real sensor and
 * GPS on the phone, a bundled fixture in the simulator. Chosen once at boot; illegal mixes can't be built.
 * Each source knows how to open itself, so nothing else branches on which one it is.
 */
sealed interface RunSource {
    /** Opens the inputs for one run; called once per run, before its session exists. */
    suspend fun open(wallClock: WallClock, scope: CoroutineScope): RunInputs

    class Live(
        val sensor: HeartRateSensor,
        val location: LocationProvider,
        val keepAlive: RunKeepAlive,
        /** Asks for the permissions a live run needs before the run screen opens (`AppModel.swift:64-74`). */
        val requestPermissions: suspend () -> Unit,
    ) : RunSource {
        // Asked here, before the run screen opens, so the prompt never covers a running clock (`AppModel.swift:66-74`);
        // the keep-alive is held only after that, because it needs the location answer (`KeepAlive`).
        override suspend fun open(wallClock: WallClock, scope: CoroutineScope): RunInputs {
            requestPermissions()
            return RunInputs(sensor, location, onRunning = keepAlive::hold, release = keepAlive::release)
        }
    }

    class Replay(val script: () -> ReplayScript) : RunSource {
        // Playback starts from `onRunning`, after the session is collecting, so the first inputs are never missed.
        override suspend fun open(wallClock: WallClock, scope: CoroutineScope): RunInputs {
            val timeline = ReplayTimeline(script(), wallClock)
            var playback: Job? = null
            return RunInputs(
                ReplaySensor(timeline), ReplayLocation(timeline),
                onRunning = { playback = scope.launch { timeline.play() } },
                release = { playback?.cancel() },
            )
        }
    }
}

/**
 * How the app is wired (`App/Sources/AppDeps.swift`): the run source, the one store, the wall clock,
 * alerts, the sample profile, and the debug recording tap. Built once in `boot()`.
 * Walking-skeleton Milestone A: the store is in memory.
 */
class AppDeps(
    val source: RunSource,
    val store: RecordingStore,
    /** Where the Summary caches a recording's reliability log (R29); the same SQL store on the phone. */
    val reliability: ReliabilityStore,
    /** What Today lists (today-screen spec): `StoredSavedRecordings` over the same SQL store on the phone. */
    val saved: SavedRecordings = SavedRecordings.None,
    val wallClock: WallClock,
    val alerts: AlertPlayer,
    /** Removes this run's lock-screen notifications once it closes (`AppModel.swift:131`). */
    val clearAlerts: () -> Unit = {},
    /** Debug builds: the tap that writes the run as a replay fixture (`AppModel.swift:142`); null in release. */
    val makeTap: (meta: ReplayMeta, fileName: String) -> RecordingTap? = { _, _ -> null },
    val log: (String) -> Unit = {},
    /** Holds the process alive for the background flush (persistence/02): `UIApplication` on the phone, none in tests. */
    val backgroundTasks: BackgroundTasks = BackgroundTasks.None,
    /** The lock screen (D11): `LiveActivityBridge::publish` on the phone, a recorder in tests, nothing in the simulator. */
    val liveActivity: (LiveActivityEvent) -> Unit = {},
    /** The profile until the runner saves one (profile-settings spec; first-launch Setup will replace this fallback). */
    val profile: Profile = sampleProfile,
    /** Where the runner's profile is kept: the SQL store on the phone. */
    val profileStore: ProfileStore = InMemoryProfileStore(),
    /** Settings' Export backup (profile-settings/02, D26): writes every run to a file in Files and returns its name; throws on failure. */
    val exportBackup: suspend () -> String = { error("no backup in this wiring") },
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
