package com.jakubfronczyk.northform.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.jakubfronczyk.northform.adapters.alerts.NotificationAlertPlayer
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.adapters.clock.IosWallClock
import com.jakubfronczyk.northform.adapters.location.KeepAlive
import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.ui.AppRoot
import com.jakubfronczyk.northform.ui.spike.SpikeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.time.Clock
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
    lateinit var model: AppModel
        private set
    private var live = false

    private fun log(line: String) {
        val stamped = "${Clock.System.now()} $line"
        NSLog("northform/boot %s", stamped) // appears in Console.app / `log collect --device`
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

        // 1  graph — no I/O yet (Milestone A: the store is in memory; SQLDelight is Phase 1)
        val wallClock = IosWallClock
        alerts = NotificationAlertPlayer(scope, ::log)
        location = LiveLocation(locationUpdates, wallClock, ::log)

        // 2  the central manager is created HERE, so willRestoreState lands in a live Kotlin delegate
        central = CoreBluetoothCentral(BLE_RESTORE_ID, ::log)

        // 3  the sensor mailbox is alive before any radio event can be delivered (delegate callbacks are
        //    dispatched on the BLE queue only after CBCentralManager's init returns)
        sensor = GattHeartRateSensor(central, wallClock)
        sensor.start(scope)

        // 4  the app's wiring: live ports on the phone, replay in the simulator (`AppDeps.swift:27-37`)
        live = !AppDeps.isSimulator
        val runAlerts = RunAlertPlayer(alerts)
        val deps = AppDeps(
            source = if (live) RunSource.Live(sensor, location, KeepAlive(), ::requestRunPermissions)
                     else RunSource.Replay { BundleFixtures.script("straight-run-3min") },
            store = InMemoryRecordingStore(),
            wallClock = wallClock,
            alerts = runAlerts,
            clearAlerts = runAlerts::clear,
        )
        model = AppModel(deps, scope, ::log)
        // Background: save what the run has buffered now (D94). Observed from Kotlin — an Obj-C API, no Swift needed.
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            model.appWentToBackground()
        }

        isBooted = true
        log("boot() end in ${started.elapsedNow()}") // 5
    }

    /** Locked-screen alerts are notifications (D105): iOS asks once, the answer never blocks a run. Location is asked by the forwarder's first update. */
    private suspend fun requestRunPermissions() {
        suspendCancellableCoroutine { cont -> alerts.requestPermission { cont.resume(Unit) } }
    }

    /** The Compose root. Swift wraps it in a UIViewControllerRepresentable and renders nothing of its own. */
    fun rootViewController(): UIViewController = ComposeUIViewController {
        val activeRun by model.activeRun.collectAsState()
        AppRoot(
            activeRun = activeRun?.session,
            onStartRun = model::startRun,
            onCloseRun = model::closeRun,
            map = { route -> RouteMap(route, showsUserLocation = live) },
            sensorScreen = { SpikeScreen(sensor, bootLog) },
        )
    }
}
