package com.jakubfronczyk.northform.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.lowPowerModeEnabled
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject
import platform.darwin.OS_LOG_TYPE_DEFAULT
import platform.darwin.__dso_handle
import platform.darwin._os_log_internal
import platform.darwin.os_log_create
import platform.darwin.os_log_t

/**
 * Structured logging on the unified log (`App/Sources/DeviceTestLog.swift`): subsystem
 * `com.jakubfronczyk.northform`, categories `app` · `run` · `location` · `polar`, so
 * `log collect --device` and Console.app filter the Kotlin app exactly like the Swift one
 * (`testing/step7-device-tests.md` §6). `os_log` is a C macro, so the exported variadic
 * `_os_log_internal` is called directly, the way Kermit's OSLogWriter does.
 */
@OptIn(ExperimentalForeignApi::class)
class OsLog(category: String) {
    private val log: os_log_t = os_log_create(SUBSYSTEM, category)

    /** `%{public}s`: the unified log redacts `%s` from third-party processes; these lines exist to be read. */
    fun notice(message: String) {
        _os_log_internal(__dso_handle.ptr, log, OS_LOG_TYPE_DEFAULT, "%{public}s", message)
    }

    companion object {
        const val SUBSYSTEM = "com.jakubfronczyk.northform"
    }
}

/** The four loggers the Swift build has (`DeviceTestLog.swift:12-13`, `PolarSensor.swift:8`, `LiveLocation.swift`). */
object DeviceLog {
    val app = OsLog("app")
    val run = OsLog("run")
    val location = OsLog("location")
    val polar = OsLog("polar")

    /** The app's foreground/background state, attached to every run line (`AppStateLog`, `:16-31`). */
    val scenePhase = MutableStateFlow("launching")

    /** `AppStateLog.runTrace`. */
    fun runTrace(message: String) = run.notice("$message [app ${scenePhase.value}]")
}

/**
 * `DeviceTestLog.swift:35` — launch, scene phases, memory warnings, Low Power Mode and location
 * permission changes, all from Kotlin: the notifications and `CLLocationManagerDelegate` are Obj-C.
 */
@OptIn(ExperimentalForeignApi::class)
class DeviceTestLog : NSObject(), CLLocationManagerDelegateProtocol {
    private val manager = CLLocationManager()

    fun start() {
        DeviceLog.app.notice("launch")
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        center.addObserverForName(UIApplicationDidReceiveMemoryWarningNotification, null, main) { DeviceLog.app.notice("memory warning") }
        center.addObserverForName(NSProcessInfoPowerStateDidChangeNotification, null, main) {
            DeviceLog.app.notice("low power mode ${if (NSProcessInfo.processInfo.lowPowerModeEnabled) "on" else "off"}")
        }
        for ((name, phase) in listOf(
            UIApplicationDidBecomeActiveNotification to "active",
            UIApplicationWillResignActiveNotification to "inactive",
            UIApplicationDidEnterBackgroundNotification to "background",
            UIApplicationWillEnterForegroundNotification to "inactive",
        )) {
            center.addObserverForName(name, null, main) {
                DeviceLog.scenePhase.value = phase
                DeviceLog.app.notice("scene phase $phase")
            }
        }
        manager.delegate = this // reports the current status, then every change
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        DeviceLog.app.notice("location authorization ${name(manager.authorizationStatus)}")
    }

    private fun name(status: CLAuthorizationStatus) = when (status) {
        kCLAuthorizationStatusNotDetermined -> "notDetermined"
        kCLAuthorizationStatusRestricted -> "restricted"
        kCLAuthorizationStatusDenied -> "denied"
        kCLAuthorizationStatusAuthorizedAlways -> "always"
        kCLAuthorizationStatusAuthorizedWhenInUse -> "whenInUse"
        else -> "unknown"
    }
}
