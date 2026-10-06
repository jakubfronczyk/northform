package com.jakubfronczyk.northform.app

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jakubfronczyk.northform.adapters.alerts.NotificationAlertPlayer
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.adapters.clock.IosWallClock
import com.jakubfronczyk.northform.adapters.liveactivity.LiveActivityBridge
import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.engine.run.LiveActivityContent
import com.jakubfronczyk.northform.engine.run.LiveActivityPhase
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.ui.spike.SpikeScreen
import platform.Foundation.NSLog
import platform.UIKit.UIViewController
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The composition root and the ONLY Kotlin entry point Swift may call besides the two seam protocols
 * (D16). `boot()` is the bootstrap contract (D9): Swift calls it as the first statement of
 * `application(_:didFinishLaunchingWithOptions:)`, synchronously, before any UI.
 *
 * Exposed to Swift as `NorthformApp.shared` (a Kotlin `object`).
 */
object NorthformApp {
    /** D9: a compile-time constant, identical across executions; never read from launchOptions. */
    const val BLE_RESTORE_ID = "com.jakubfronczyk.northform.ble"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootLog = ArrayList<String>()

    var isBooted = false
        private set
    lateinit var central: CoreBluetoothCentral
        private set
    lateinit var sensor: GattHeartRateSensor
        private set
    lateinit var location: LiveLocation
        private set
    lateinit var alerts: NotificationAlertPlayer
        private set

    private fun log(line: String) {
        val stamped = "${Clock.System.now()} $line"
        NSLog("northform/boot %s", stamped) // appears in Console.app / `log collect --device`, spike gate 4b proof
        bootLog += stamped
    }

    /**
     * D9 steps 1–5. Synchronous; no I/O awaited. Target < 100 ms (spike step 7 logs the wall time).
     * Idempotent: a second call is a no-op (iOS calls didFinishLaunching once per process; tests may call twice).
     */
    fun boot(locationUpdates: LocationUpdates) {
        if (isBooted) return
        val started = TimeSource.Monotonic.markNow()
        log("boot() start")

        // 1  graph — no I/O yet (SQLDelight store comes in Phase 0 step 6)
        val wallClock = IosWallClock
        alerts = NotificationAlertPlayer(scope, ::log)
        location = LiveLocation(locationUpdates, wallClock, ::log)

        // 2  the central manager is created HERE, so willRestoreState lands in a live Kotlin delegate
        central = CoreBluetoothCentral(BLE_RESTORE_ID, ::log)

        // 3  the sensor mailbox is alive before any radio event can be delivered (delegate callbacks are
        //    dispatched on the BLE queue only after CBCentralManager's init returns)
        sensor = GattHeartRateSensor(central, wallClock)
        sensor.start(scope)

        // 4  TODO(spike): Phase 0 step 6 — if the store has an unfinished run, hold the link, do NOT resume
        //    recording (R28: resume is the user's choice on reopen).

        isBooted = true
        log("boot() end in ${started.elapsedNow()}") // 5
    }

    /** The Compose root. Swift wraps it in a UIViewControllerRepresentable and renders nothing of its own. */
    fun rootViewController(): UIViewController = ComposeUIViewController {
        SpikeScreen(sensor, bootLog)
    }

    /**
     * Spike step 9 only: drive the Live Activity bridge from a timer so the Swift controller + widget can
     * be proven independently of the run session. Phase 1 lane G replaces this with session-derived content.
     */
    fun startLiveActivityDemo() {
        val startedAt = Clock.System.now()
        scope.launch {
            var ticks = 0L
            LiveActivityBridge.start(demoContent(startedAt.toEpochMilliseconds(), 0))
            while (ticks < 24) { // 2 minutes
                delay(5.seconds); ticks += 5
                LiveActivityBridge.update(demoContent(startedAt.toEpochMilliseconds(), ticks))
            }
            LiveActivityBridge.end(demoContent(startedAt.toEpochMilliseconds(), ticks).copy(phase = LiveActivityPhase.Done))
        }
    }

    private fun demoContent(startedAtMs: Long, sec: Long) = LiveActivityContent(
        phase = LiveActivityPhase.Active,
        startedAtEpochMs = startedAtMs,
        totalTimeSec = sec,
        distanceM = sec * 2.8,
        splitPaceSecPerKm = if (sec > 20) 360 else null,
        hrBpm = 140 + (sec % 7).toInt(),
        hrLost = false,
        gpsLost = false,
    )
}
