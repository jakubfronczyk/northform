package com.jakubfronczyk.northform.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.jakubfronczyk.northform.adapters.alerts.NotificationAlertPlayer
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.adapters.clock.IosWallClock
import com.jakubfronczyk.northform.adapters.location.KeepAlive
import com.jakubfronczyk.northform.adapters.location.LiveLocation
import com.jakubfronczyk.northform.adapters.location.LocationAuthorization
import com.jakubfronczyk.northform.adapters.location.LocationAuthorizationMonitor
import com.jakubfronczyk.northform.adapters.location.openAppSettings
import com.jakubfronczyk.northform.adapters.location.LocationUpdates
import com.jakubfronczyk.northform.adapters.location.requestWhenInUseAuthorization
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import com.jakubfronczyk.northform.persistence.SqlRecordingStore
import com.jakubfronczyk.northform.persistence.nativeDriver
import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.ui.AppRoot
import com.jakubfronczyk.northform.ui.crash.CrashPromptScreen
import com.jakubfronczyk.northform.ui.run.GateSensor
import com.jakubfronczyk.northform.ui.sensor.SensorScreen
import com.jakubfronczyk.northform.ui.sensor.SensorScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    lateinit var sensorDetails: LinkSensorDetails
        private set
    lateinit var location: LiveLocation
        private set
    private lateinit var locationAuthorization: LocationAuthorizationMonitor
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

        // 1  graph — no I/O awaited. Opening the database is the one file touch (schema v1 is created on the first launch; D7/D12).
        val wallClock = IosWallClock
        alerts = NotificationAlertPlayer(scope, DeviceLog.run::notice)
        locationAuthorization = LocationAuthorizationMonitor() // main thread: boot() runs in didFinishLaunching
        location = LiveLocation(locationUpdates, wallClock, locationAuthorization.state, DeviceLog.location::notice)

        // 2  the central manager is created HERE, so willRestoreState lands in a live Kotlin delegate
        central = CoreBluetoothCentral(BLE_RESTORE_ID, DeviceLog.polar::notice)
        DeviceLog.app.notice("CBCentralManager created (restore id = $BLE_RESTORE_ID)")

        // 3  the sensor mailbox is alive before any radio event can be delivered (delegate callbacks are
        //    dispatched on the BLE queue only after CBCentralManager's init returns)
        // SU3: the link decides when a sensor counts as paired (adapters/01); the app seeds it from what it
        // stored, and only persists and shows it.
        sensor = GattHeartRateSensor(central, wallClock, initial = LinkSensorDetails.storedLinkState())
        sensorDetails = LinkSensorDetails(sensor, scope)
        sensor.start(scope)
        scope.launch { sensor.states().collect { DeviceLog.polar.notice("state ${it.state}") } }

        // 4  the app's wiring: live ports on the phone, replay in the simulator (`AppDeps.swift:27-37`)
        live = !AppDeps.isSimulator
        val runAlerts = RunAlertPlayer(alerts, DeviceLog::runTrace)
        val deps = AppDeps(
            source = if (live) RunSource.Live(sensor, location, KeepAlive(DeviceLog.location::notice).asRunKeepAlive(), ::requestRunPermissions)
                     else RunSource.Replay { BundleFixtures.script("straight-run-3min") },
            store = LoggingStore(SqlRecordingStore(nativeDriver()), DeviceLog::runTrace),
            wallClock = wallClock,
            alerts = runAlerts,
            clearAlerts = runAlerts::clear,
            // Debug builds write each run as a replay fixture into Documents/Recordings (visible in the Files app).
            makeTap = { meta, name -> if (Platform.isDebugBinary) RecordingTap(meta) { Documents.write(Documents.recordingPath(name), it) } else null },
            log = DeviceLog::runTrace,
            backgroundTasks = UiBackgroundTasks(DeviceLog.app::notice),
        )
        model = AppModel(deps, scope)
        model.checkUnfinished() // 4  store.unfinished()? → remembered for the R28 prompt; nothing resumes on its own (D9)
        // Background: save what the run has buffered now (D94). Observed from Kotlin — an Obj-C API, no Swift needed.
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
            model.appWentToBackground()
        }
        // The remembered sensor reconnects at launch (`AppDeps.swift:34-36`).
        if (live) connectPairedSensor()

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

    /**
     * adapters/03, for the gate (run-flow-ui/01): `LocationHint.of(it)` says why GPS is ✗ while location is denied or restricted, `null`
     * when nothing stands in the way. Changes live as the person flips the switch in Settings.
     */
    fun locationAuthorizationState(): StateFlow<LocationAuthorization> = locationAuthorization.state

    /** The Settings deep link the gate offers next to a `Denied` hint (`LocationHint.canOpenSettings`). */
    fun openLocationSettings() = openAppSettings()

    /** Connects the paired sensor, if any; a timeout is not an error here (the link keeps trying). */
    private fun connectPairedSensor() {
        val id = sensor.linkState.value.paired ?: return
        scope.launch { runCatching { sensor.connect(id) } }
    }

    /** E1: what the gate shows about the sensor. Replay has no link, so it says nothing (null = unknown). */
    private fun gateSensorStates(): Flow<GateSensor?> =
        if (!live) flowOf(null)
        else combine(sensor.states(), sensorDetails.paired) { change, paired ->
            val connected = change.state as? SensorState.Connected
            GateSensor(connected = connected != null, batteryPercent = connected?.battery, paired = paired != null)
        }

    /** `KeepAlive` is an `:adapters` class; `RunKeepAlive` is the app's seam for it (a fake in tests). */
    private fun KeepAlive.asRunKeepAlive() = object : RunKeepAlive {
        override fun hold() = this@asRunKeepAlive.hold()
        override fun release() = this@asRunKeepAlive.release()
    }

    /** The Compose root. Swift wraps it in a UIViewControllerRepresentable and renders nothing of its own. */
    fun rootViewController(): UIViewController = ComposeUIViewController {
        val activeRun by model.activeRun.collectAsState()
        val unfinished by model.unfinished.collectAsState()
        val gateSensor by remember { gateSensorStates() }.collectAsState(null)
        AppRoot(
            activeRun = activeRun?.session,
            onStartRun = model::startRun,
            onCloseRun = model::closeRun,
            map = { route -> RouteMap(route, showsUserLocation = live) },
            // One model per opening (SU10): its collectors and 30 s search live exactly as long as the screen.
            sensorScreen = { close -> SensorScreen(remember { SensorScreenModel(sensor, sensorDetails) }, onClose = close) },
            gateSensor = gateSensor,
            connectSensor = ::connectPairedSensor,
            crashPrompt = unfinished?.let { run -> { CrashPromptScreen(run.facts, onChoice = model::resolveUnfinished) } },
        )
    }
}
