package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.ui.rememberSetupPairing
import androidx.compose.runtime.Composable
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.ui.SetupPermissions
import com.jakubfronczyk.northform.ui.SetupScreen
import platform.Foundation.NSLocale
import com.jakubfronczyk.northform.ui.ZoneEditorScreen
import com.jakubfronczyk.northform.ui.ProfileEditScreen
import platform.Foundation.NSBundle
import com.jakubfronczyk.northform.ui.SettingsModel
import kotlin.time.Instant
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import com.jakubfronczyk.northform.persistence.BackupCodec
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.jakubfronczyk.northform.adapters.alerts.NotificationAlertPlayer
import com.jakubfronczyk.northform.adapters.ble.BluetoothAuthorization
import com.jakubfronczyk.northform.adapters.ble.CoreBluetoothCentral
import com.jakubfronczyk.northform.adapters.clock.IosWallClock
import com.jakubfronczyk.northform.adapters.liveactivity.LiveActivityBridge
import com.jakubfronczyk.northform.adapters.location.BackgroundActivityKeepAlive
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
import com.jakubfronczyk.northform.ui.HistoryModel
import com.jakubfronczyk.northform.ui.TodayModel
import com.jakubfronczyk.northform.engine.store.StoredSavedRecordings
import com.jakubfronczyk.northform.ui.run.SummaryScreen
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
import kotlinx.coroutines.flow.map
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

    /**
     * For now: true starts every install with the sample profile (born 14.03.1996, male, 78 kg, 182 cm, max 187, rest 60),
     * so the app skips Setup and opens on Today. Set false to see first-launch Setup again. See [HardcodedProfile].
     */
    const val HARDCODED_PROFILE = true

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
    /** The wiring [model] runs on; Today reads its clock and zone from here too (one source for both). */
    private lateinit var deps: AppDeps
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
        location = LiveLocation(locationUpdates, wallClock, locationAuthorization.state, BackgroundActivityKeepAlive(DeviceLog.location::notice), DeviceLog.location::notice)

        // 2  the central manager is created HERE, so willRestoreState lands in a live Kotlin delegate — once iOS has an
        //    answer about Bluetooth (ADR 0005, amended). Undetermined means a fresh install: no sensor can be paired, so
        //    nothing can be restored, and creating it would put iOS's prompt over Setup. Setup's Allow creates it then.
        central = CoreBluetoothCentral(BLE_RESTORE_ID, DeviceLog.polar::notice)
        val bluetooth = BluetoothAuthorization.current()
        central.activateAtBoot(bluetooth)
        DeviceLog.app.notice("Bluetooth authorization $bluetooth: CBCentralManager ${if (central.isActive) "created (restore id = $BLE_RESTORE_ID)" else "waits for Setup's Allow"}")

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
        val sqlStore = SqlRecordingStore(nativeDriver())
        deps = AppDeps(
            source = if (live) RunSource.Live(sensor, location, ::requestRunPermissions)
                     else RunSource.Replay { BundleFixtures.script("straight-run-3min") },
            store = LoggingStore(sqlStore, DeviceLog::runTrace),
            reliability = sqlStore,
            profileStore = if (HARDCODED_PROFILE) HardcodedProfile(sqlStore, AppDeps.sampleProfile) else sqlStore,
            exportBackup = { exportBackup(sqlStore, wallClock.now()) },
            saved = StoredSavedRecordings(sqlStore, sqlStore, DeviceLog::runTrace),
            wallClock = wallClock,
            alerts = runAlerts,
            clearAlerts = runAlerts::clear,
            // Debug builds write each run as a replay fixture into Documents/Recordings (visible in the Files app).
            makeTap = { meta, name -> if (Platform.isDebugBinary) RecordingTap(meta) { Documents.write(Documents.recordingPath(name), it) } else null },
            log = DeviceLog::runTrace,
            backgroundTasks = UiBackgroundTasks(DeviceLog.app::notice),
            liveActivity = LiveActivityBridge::publish, // D11: phone and simulator alike (the simulator renders Live Activities too)
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
     * `LiveLocation` holds once the run collects GPS only becomes active with location authorization. Locked-screen alerts are notifications
     * (D105): iOS asks once, the answer never blocks a run.
     */
    private suspend fun requestRunPermissions() {
        DeviceLog.app.notice("location authorization answered: ${requestWhenInUseAuthorization()}")
        suspendCancellableCoroutine { cont -> alerts.requestPermission { cont.resume(Unit) } }
    }

    /**
     * adapters/03, for the gate (run-flow-ui/07): mapped by `asPermission()` into the gate's model, which says why GPS is ✗
     * while location is denied or restricted. Changes live as the person flips the switch in Settings.
     */
    fun locationAuthorizationState(): StateFlow<LocationAuthorization> = locationAuthorization.state

    /** The Settings deep link the gate offers beside a denied location (`GateModel.LocationReason.openSettings`). */
    fun openLocationSettings() = openAppSettings()

    /**
     * Settings' Export backup (D26): every run and its raw streams as one JSON file at the top of Documents, visible in
     * the Files app (`UIFileSharingEnabled`). Returns the file name; throws when it cannot be written.
     */
    private suspend fun exportBackup(store: SqlRecordingStore, now: Instant): String {
        // en_US_POSIX: a Buddhist or Japanese calendar would otherwise write "2569" or "0008" for the year.
        val stamp = NSDateFormatter().apply { locale = NSLocale(localeIdentifier = "en_US_POSIX"); dateFormat = "yyyyMMdd-HHmm" }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(now.epochSeconds.toDouble()))
        val name = "northform-backup-$stamp.json"
        val text = BackupCodec.encode(store.exportAll(now))
        check(Documents.write(Documents.topLevelPath(name), text)) { "could not write $name" }
        return name
    }

    /**
     * first-launch-setup: the four steps over the real ports — the profile through the app model, Bluetooth by creating
     * the central (iOS asks then, ADR 0005 amended; a no-op once it exists), Location through the
     * same request a run start makes (it does nothing once decided, so never two prompts), the sensor through the Sensor
     * screen's model, created and followed only while the Pair step is up. [profile] is null until step 1 has saved one
     * (the fallback must not prefill the form).
     */
    @Composable
    private fun Setup(profile: Profile?, finish: () -> Unit) {
        val location by locationAuthorization.state.collectAsState()
        val bluetooth by central.authorization.collectAsState()
        SetupScreen(
            profile = profile, now = deps.wallClock.now(), tzOffset = deps.tzOffset(), onSaveProfile = model::saveProfile,
            permissions = SetupPermissions.of(bluetooth.asPermission(), location.asPermission()),
            allowBluetooth = central::activate,
            allowLocation = { scope.launch { DeviceLog.app.notice("location authorization answered: ${requestWhenInUseAuthorization()}") } },
            openSettings = ::openAppSettings,
            pairing = { rememberSetupPairing(::pairingModel) },
            finish = finish,
        )
    }

    /**
     * The Sensor screen's model, for the Sensor sheet and Setup's Pair step. Pairing cannot work without the radio, so
     * opening either is the runner asking for Bluetooth: it creates the central if Setup's Allow was skipped (iOS asks
     * then; a no-op otherwise). The search it starts waits in the link until the central powers on.
     */
    private fun pairingModel(): SensorScreenModel {
        central.activate()
        return SensorScreenModel(sensor, sensorDetails)
    }

    /** "Version 1.0 (14)" from the bundle (Info.plist), as the Settings artboard prints it. */
    private fun appVersion(): String {
        val info = NSBundle.mainBundle.infoDictionary
        return "Version ${info?.get("CFBundleShortVersionString") ?: "?"} (${info?.get("CFBundleVersion") ?: "?"})"
    }

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
            GateSensor(connected = connected != null, batteryPercent = connected?.battery, paired = paired != null, name = paired?.name)
        }

    /** The Compose root. Swift wraps it in a UIViewControllerRepresentable and renders nothing of its own. */
    fun rootViewController(): UIViewController = ComposeUIViewController {
        val activeRun by model.activeRun.collectAsState()
        val unfinished by model.unfinished.collectAsState()
        val summary by model.summary.collectAsState()
        val gateSensor by remember { gateSensorStates() }.collectAsState(null)
        val locationAuth by locationAuthorizationState().collectAsState()
        val saved by model.saved.collectAsState()
        val sensorName by remember { if (live) sensorDetails.paired.map { it?.name } else flowOf(null) }.collectAsState(null)
        val paired by remember { if (live) sensorDetails.paired else flowOf(null) }.collectAsState(null)
        val firmware by remember { if (live) sensorDetails.firmware else flowOf(null) }.collectAsState(null)
        val profile by model.profile.collectAsState()
        val needsSetup by model.needsSetup.collectAsState()
        val export by model.export.collectAsState()
        val profileSaveError by model.profileSaveError.collectAsState()
        AppRoot(
            activeRun = activeRun?.session,
            today = TodayModel.from(saved.recordings, gateSensor, sensorName, deps.wallClock.now(), deps.tzOffset()),
            history = { filter -> HistoryModel.from(saved, filter, deps.wallClock.now()) },
            retryHistory = model::refreshSaved,
            openRecording = model::openRecording,
            settings = SettingsModel.from(profile, deps.wallClock.now(), deps.tzOffset(), paired, gateSensor?.connected == true, gateSensor?.batteryPercent, firmware, export, appVersion(), profileSaveError),
            exportBackup = model::exportBackup,
            needsSetup = needsSetup,
            setup = { finish -> Setup(if (needsSetup == true) null else profile, finish) },
            zoneEditor = { close -> ZoneEditorScreen(profile, backLabel = "Back to Settings", onSave = { model.saveProfile(it); close() }, back = close) },
            profileEditor = { close -> ProfileEditScreen(profile, deps.wallClock.now(), deps.tzOffset(), onSave = { model.saveProfile(it); close() }, back = close) },
            forgetSensor = { scope.launch { runCatching { sensorDetails.forget() }.onFailure { DeviceLog.app.notice("forget failed: $it") } } },
            onStartRun = model::startRun,
            onCloseRun = model::closeRun,
            map = { route -> RouteMap(route, showsUserLocation = live) },
            // One model per opening (SU10): its collectors and 30 s search live exactly as long as the screen.
            sensorScreen = { close -> SensorScreen(remember { pairingModel() }, onClose = close) },
            gateSensor = gateSensor,
            connectSensor = ::connectPairedSensor,
            // Replay feeds its own fixes whatever iOS says, so the gate has no location reason there (null = unknown).
            gateLocation = if (live) locationAuth.asPermission() else null,
            openLocationSettings = ::openLocationSettings,
            crashPrompt = unfinished?.let { run -> { CrashPromptScreen(run.facts, now = IosWallClock::now, onChoice = model::resolveUnfinished) } },
            summary = summary?.let { session -> { SummaryScreen(session.state.collectAsState().value, edits = session.edits.collectAsState().value, onEdit = model::editSummary, onSave = model::saveSummary, onDelete = model::deleteSummary, onAcceptHrMax = model::acceptHrMax, onRetry = model::retrySummary, map = { route -> RouteMap(route, showsUserLocation = false) }, review = session.review, onBack = model::closeSummary) } },
        )
    }
}
