package com.jakubfronczyk.northform.adapters.location

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import platform.CoreLocation.CLLocation
import platform.Foundation.timeIntervalSince1970
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * GPS as the engine sees it (D10). The brain for location: mapping, filtering and the D101 restart.
 * The Swift forwarder only hands over `CLLocation + stationary`.
 *
 * D101: when the updates end by themselves (error, lost signal) while `fixes()` still has a collector,
 * restart after 2 · 4 · 8 · 16 · 30 s (capped), reset by the first fix; permission denied ⇒ no restart,
 * the run shows GPS lost. Every restart is logged with its attempt number. `delay` runs on the
 * collector's dispatcher, so a test on a virtual clock drives the backoff without waiting.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveLocation(
    private val updates: LocationUpdates,
    private val wallClock: WallClock,
    private val log: (String) -> Unit = {},
    private val authorization: () -> LocationAuthorization = { LocationAuthorization.current() },
) : LocationProvider {

    /** A cold stream per call; `awaitClose` is mandatory in `callbackFlow` and is where the forwarder stops. */
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val streamStart = wallClock.now()
        var attempt = 0
        var restart: Job? = null
        lateinit var sink: LocationSink
        sink = object : LocationSink {
            override fun onUpdate(location: CLLocation, stationary: Boolean) {
                val fix = location.toFix(stationary) ?: return
                if (fix.t < streamStart) return // cached fix from before this stream began (D92 rule)
                attempt = 0 // a fix proves the stream is healthy again
                trySend(fix)
            }

            override fun onEnded(message: String?) {
                log("live updates ended: ${message ?: "finished"}")
                if (authorization() == LocationAuthorization.Denied) {
                    log("location denied: no restart")
                    return
                }
                val wait = backoff[minOf(attempt, backoff.lastIndex)]
                attempt += 1
                log("restart $attempt in ${wait.inWholeSeconds} s")
                restart = launch {
                    delay(wait)
                    updates.start(sink)
                }
            }
        }
        updates.start(sink)
        awaitClose {
            restart?.cancel()
            updates.stop()
        }
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

    companion object {
        /** D101: 2 · 4 · 8 · 16 · 30 s, the last value repeats. */
        val backoff: List<Duration> = listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds)
    }
}
