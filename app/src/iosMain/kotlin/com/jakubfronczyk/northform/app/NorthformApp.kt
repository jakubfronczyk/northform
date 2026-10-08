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
import com.jakubfronczyk.northform.adapters.location.requestWhenInUseAuthorization
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.ui.AppRoot
import com.jakubfronczyk.northform.ui.sensor.SensorScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController
import kotlin.coroutines.resume
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
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
    private val deviceTestLog = DeviceTestLog()

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

    /**
     * D9 steps 1–5. Synchronous; no I/O awaited. Target < 100 ms (the `boot() end` line carries the wall time).
     * Idempotent: a second call is a no-op (iOS calls didFinishLaunching once per process; tests may call twice).
     */
    @OptIn(ExperimentalNativeApi::class)
    fun boot(locationUpdates: LocationUpdates) {
        if (isBooted) return
        val started = TimeSource.Monotonic.markNow()
        deviceTestLog.start() // "launch", then scene phases, memory warnings, power mode, location authorization
        DeviceLog.app.notice("boot() start")

        // 1  graph — no I/O yet (Milestone A: the store is in memory; SQLDelight is Phase 1)
        val wallClock = IosWallClock
        alerts = NotificationAlertPlayer(scope, DeviceLog.run::notice)
        location = LiveLocation(locationUpdates, wallClock, DeviceLog.location::notice)

        // 2  the central manager is created HERE, so willRestoreState lands in a live Kotlin delegate
        central = CoreBluetoothCentral(BLE_RESTORE_ID, DeviceLog.polar::notice)
        DeviceLog.app.notice("CBCentralManager created (restore id = $BLE_RESTORE_ID)")

        // 3  the sensor mailbox is alive before any radio event can be delivered (delegate callbacks are
        //    dispatched on the BLE queue only after CBCentralManager's init returns)
        sensor = GattHeartRateSensor(central, wallClock)
        sensor.start(scope)
        scope.launch { sensor.states().collect { DeviceLog.polar.notice("state ${it.state}") } }

        // 4  the app's wiring: live ports on the phone, replay in the simulator (`AppDeps.swift:27-37`)
        live = !AppDeps.isSimulator
        val runAlerts = RunAlertPlayer(alerts, DeviceLog::runTrace)
        val deps = AppDeps(
            source = if (live) RunSource.Live(sensor, location, KeepAlive(DeviceLog.location::notice), ::requestRunPermissions)
                     else RunSource.Replay { BundleFixtures.script("straight-run-3min") },
            store = LoggingStore(InMemoryRecordingStore(), DeviceLog::runTrace),
            wallClock = wallClock,
            alerts = runAlerts,
            clearAlerts = runAlerts::clear,
            // Debug builds write each run as a replay fixture into Documents/Recordings (visible in the Files app).
            makeTap = { meta, name -> if (Platform.isDebugBinary) RecordingTap(meta) { Documents.write(Documents.recordingPath(name), it) } else null },
            log = DeviceLog::runTrace,
        )
        model = AppModel(deps, scope)
        // Background: save what the run has buffered now (D94). Observed from Kotlin — an Obj-C API, no Swift needed.
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            model.appWentToBackground()
        }
        // The remembered sensor reconnects at launch (`AppDeps.swift:34-36`).
        if (live) PairedSensor.id?.let { id -> scope.launch { runCatching { sensor.connect(SensorId(id)) } } }

        isBooted = true
        DeviceLog.app.notice("boot() end in ${started.elapsedNow()}") // 5
    }

    /**
     * Asked before the run screen opens, location first (SU4 order, `AppModel.swift:66-74`): the keep-alive
     * session only becomes active with location authorization. Locked-screen alerts are notifications
     * (D105): iOS asks once, the answer never blocks a run.
     */
    private suspend fun requestRunPermissions() {
        DeviceLog.app.notice("location authorization answered: ${requestWhenInUseAuthorization()}")
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
            sensorScreen = { close -> SensorScreen(sensor, onConnected = { PairedSensor.id = it.value }, onClose = close) },
        )
    }
}
