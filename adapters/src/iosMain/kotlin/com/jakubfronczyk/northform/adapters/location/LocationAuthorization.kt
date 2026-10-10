package com.jakubfronczyk.northform.adapters.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The four states the app cares about (`CoreLocationSource.swift:61-67`, grown by `Restricted` for
 * adapters/03): `Denied` the person can undo in Settings; `Restricted` (Screen Time, a profile) they cannot.
 */
enum class LocationAuthorization {
    NotDetermined, WhenInUse, Denied, Restricted;

    /** No fixes will ever come in this state; D101: no restart, the gate says why. */
    val blocksLocation: Boolean get() = this == Denied || this == Restricted

    companion object {
        fun current(): LocationAuthorization = of(CLLocationManager().authorizationStatus)

        fun of(status: CLAuthorizationStatus) = when (status) {
            kCLAuthorizationStatusNotDetermined -> NotDetermined
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> WhenInUse
            kCLAuthorizationStatusRestricted -> Restricted
            else -> Denied
        }
    }
}

/**
 * Asks for "While Using" and waits for the answer (`CoreLocationSource.swift:71-94`). Asked before the
 * run screen opens and before the keep-alive session: a `CLBackgroundActivitySession` only becomes
 * active for an app with effective authorization. Core Location delivers the delegate callback on the
 * thread that created the manager, so the manager lives on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun requestWhenInUseAuthorization(): LocationAuthorization {
    val current = LocationAuthorization.current()
    if (current != LocationAuthorization.NotDetermined) return current
    return suspendCancellableCoroutine { cont ->
        dispatch_async(dispatch_get_main_queue()) {
            val request = AuthorizationRequest { status -> if (cont.isActive) cont.resume(status) }
            cont.invokeOnCancellation { request.manager.delegate = null }
            request.manager.requestWhenInUseAuthorization()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class AuthorizationRequest(private val onAnswer: (LocationAuthorization) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    val manager = CLLocationManager().also { it.delegate = this }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val status = LocationAuthorization.of(manager.authorizationStatus)
        if (status == LocationAuthorization.NotDetermined) return // the first callback reports the current (unasked) state
        manager.delegate = null
        onAnswer(status)
    }
}

/**
 * The authorization as a `StateFlow`, for the life of the process (adapters/03): `LiveLocation`
 * restarts GPS when it turns to `WhenInUse` mid-run, the gate shows why GPS is ✗ while it is denied.
 * Kotlin `iosMain` owns the `CLLocationManagerDelegate` (D10: the Swift forwarder stays dumb; D16
 * forbids `CLLocationManager` in Swift). Create it on the main thread (`boot()` is), like every
 * Core Location object here; the delegate reports the current status at once, then every change —
 * including the one made in Settings while the app is in the background.
 */
@OptIn(ExperimentalForeignApi::class)
class LocationAuthorizationMonitor : NSObject(), CLLocationManagerDelegateProtocol {
    private val _state = MutableStateFlow(LocationAuthorization.current())
    val state: StateFlow<LocationAuthorization> = _state.asStateFlow()
    private val manager = CLLocationManager()

    init {
        check(NSThread.isMainThread) { "LocationAuthorizationMonitor must be created on the main thread" }
        manager.delegate = this
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        _state.value = LocationAuthorization.of(manager.authorizationStatus)
    }
}

/** The Settings deep link for a `Denied` location: iOS opens this app's own page. No-op if it cannot open. */
fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
