package com.jakubfronczyk.northform.adapters.location

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLBackgroundActivitySession
import platform.CoreLocation.CLBackgroundActivitySessionDiagnostic
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Keeps the process alive while locked: a `CLBackgroundActivitySession` (iOS 17+), which keeps a
 * When-In-Use app "in use" and shows the blue pill. Facts from Apple's `CLBackgroundActivitySession.h`:
 *
 * - `init` is `NS_UNAVAILABLE`: a session exists only through the class method
 *   `backgroundActivitySession`. Kotlin/Native still generates `CLBackgroundActivitySession()`, and that
 *   plain `[[alloc] init]` is an object the location daemon never hears about: the first on-device walk
 *   lost GPS the moment the phone locked (2026-10-08). Swift's `CLBackgroundActivitySession()` is the
 *   refined overlay of the class method, which is why the Swift build worked.
 * - A new session only becomes active with effective authorization, `location` in `UIBackgroundModes`,
 *   and while the app is in the foreground and in direct use: hold it at run start, after the location
 *   permission was answered (`AppModel`).
 * - The iOS 18 variant reports diagnostics; they are logged under `location`, so a locked walk explains
 *   itself afterwards (`insufficientlyInUse`, `authorizationDenied`, …).
 *
 * Created on the main thread like the Swift build (Core Location objects expect a run loop).
 */
@OptIn(ExperimentalForeignApi::class)
class KeepAlive(private val log: (String) -> Unit = {}) {
    private var session: CLBackgroundActivitySession? = null

    fun hold() = onMain {
        if (session != null) return@onMain
        session = CLBackgroundActivitySession.backgroundActivitySessionWithQueue(dispatch_get_main_queue()) { diagnostic ->
            log("background session ${diagnostic?.describe() ?: "no diagnostic"}")
        }
        log("background session created")
    }

    fun release() = onMain {
        session?.invalidate()
        session = null
        log("background session invalidated")
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun CLBackgroundActivitySessionDiagnostic.describe(): String {
        val reasons = listOfNotNull(
            "authorizationDenied".takeIf { authorizationDenied },
            "authorizationDeniedGlobally".takeIf { authorizationDeniedGlobally },
            "authorizationRestricted".takeIf { authorizationRestricted },
            "insufficientlyInUse".takeIf { insufficientlyInUse },
            "serviceSessionRequired".takeIf { serviceSessionRequired },
            "authorizationRequestInProgress".takeIf { authorizationRequestInProgress },
        )
        return if (reasons.isEmpty()) "active" else "suspended: ${reasons.joinToString()}"
    }
}
