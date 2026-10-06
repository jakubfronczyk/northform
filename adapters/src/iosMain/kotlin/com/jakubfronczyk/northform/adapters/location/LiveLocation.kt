package com.jakubfronczyk.northform.adapters.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.WallClock
import platform.CoreLocation.CLBackgroundActivitySession
import platform.CoreLocation.CLLocation
import platform.Foundation.timeIntervalSince1970
import kotlin.time.Instant

/**
 * GPS as the engine sees it (D10). The brain for location: mapping, filtering, and (Phase 0) the D101
 * restart backoff and keep-alive. The Swift forwarder only hands over `CLLocation + stationary`.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveLocation(
    private val updates: LocationUpdates,
    private val wallClock: WallClock,
    private val log: (String) -> Unit = {},
) : LocationProvider {

    /** A cold stream per call; `awaitClose` is mandatory in `callbackFlow` and is where the forwarder stops. */
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val streamStart = wallClock.now()
        val sink = object : LocationSink {
            override fun onUpdate(location: CLLocation, stationary: Boolean) {
                val fix = location.toFix(stationary) ?: return
                if (fix.t < streamStart) return // cached fix from before this stream began (D92 rule)
                trySend(fix)
            }
            override fun onEnded(message: String?) {
                log("live updates ended: $message")
                // TODO(spike): Phase 0 — D101 restart with backoff 2·4·8·16·30 s while collectors remain;
                // permission denied ⇒ no restart. The spike only logs.
            }
        }
        updates.start(sink)
        awaitClose { updates.stop() }
    }

    private fun CLLocation.toFix(stationary: Boolean): LocationFix? {
        if (horizontalAccuracy < 0) return null // Core Location: negative = invalid
        val (lat, lon) = coordinate.useContents { latitude to longitude }
        fun valid(v: Double) = v.takeIf { it >= 0 }
        return LocationFix(
            t = Instant.fromEpochMilliseconds((timestamp.timeIntervalSince1970 * 1000).toLong()),
            latitude = lat,
            longitude = lon,
            horizontalAccuracy = horizontalAccuracy,
            altitude = if (verticalAccuracy >= 0) altitude else null,
            speed = valid(speed),
            stationary = stationary,
        )
    }
}

/**
 * Keeps the process alive while locked (Obj-C `CLBackgroundActivitySession`, iOS 17+:
 * https://developer.apple.com/documentation/corelocation/clbackgroundactivitysession-4nl4y).
 * Create in the FOREGROUND when a run starts (Apple: a new session must start from the foreground; only
 * an existing one can be rejoined from the background — WWDC23 10180). `location` must be in
 * UIBackgroundModes for it to work.
 */
class KeepAlive {
    private var session: CLBackgroundActivitySession? = null
    fun hold() { if (session == null) session = CLBackgroundActivitySession() }
    fun release() { session?.invalidate(); session = null }
}
