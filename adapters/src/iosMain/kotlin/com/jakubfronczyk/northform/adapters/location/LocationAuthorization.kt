package com.jakubfronczyk.northform.adapters.location

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The three states the app cares about (`CoreLocationSource.swift:61-67`). */
enum class LocationAuthorization {
    NotDetermined, WhenInUse, Denied;

    companion object {
        fun current(): LocationAuthorization = of(CLLocationManager().authorizationStatus)

        fun of(status: CLAuthorizationStatus) = when (status) {
            kCLAuthorizationStatusNotDetermined -> NotDetermined
            kCLAuthorizationStatusAuthorizedWhenInUse, kCLAuthorizationStatusAuthorizedAlways -> WhenInUse
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
