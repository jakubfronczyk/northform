package com.jakubfronczyk.northform.adapters.location

import kotlin.math.roundToInt
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
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
 *
 * adapters/03: `authorization` is watched for the whole stream. The moment it turns to `WhenInUse`
 * (granted in Settings mid-run, the app comes back) the forwarder is restarted at once, backoff reset,
 * so GPS returns without an app restart. A change to `Denied`/`Restricted` does nothing by itself:
 * the forwarder's stream ends (or goes quiet) and `onEnded` refuses the restart.
 */
@OptIn(ExperimentalForeignApi::class)
class LiveLocation(
    private val updates: LocationUpdates,
    private val wallClock: WallClock,
    /** Required: a snapshot that never changes would silently turn off restart-on-grant (adapters/03). */
    private val authorization: StateFlow<LocationAuthorization>,
    private val log: (String) -> Unit = {},
) : LocationProvider {

    /** A cold stream per call; `awaitClose` is mandatory in `callbackFlow` and is where the forwarder stops. */
    override fun fixes(): Flow<LocationFix> = callbackFlow {
        val streamStart = wallClock.now()
        var attempt = 0
        var restart: Job? = null
        lateinit var sink: LocationSink
        sink = object : LocationSink {
            // DEBUG (device/01 walk, 2026-10-18): the first fix, every 10th, and every stationary flip, so the log shows the
            // GPS stream's shape without a line per second. Remove with the other debug lines after the device session.
            var fixes = 0
            var wasStationary: Boolean? = null
            override fun onUpdate(location: CLLocation, stationary: Boolean) {
                val fix = location.toFix(stationary) ?: run { log("fix dropped: invalid accuracy/coordinate"); return }
                if (fix.t < streamStart) { log("fix dropped: cached from before the stream (D92)"); return }
                attempt = 0 // a fix proves the stream is healthy again
                fixes += 1
                if (fixes == 1 || fixes % 10 == 0 || wasStationary != stationary) {
                    log("fix #$fixes acc ${fix.horizontalAccuracy.toInt()} m speed ${fix.speed?.let { (it * 10).roundToInt() / 10.0 } ?: "-"} m/s stationary $stationary alt ${fix.altitude?.toInt() ?: "-"}")
                }
                wasStationary = stationary
                trySend(fix)
            }

            override fun onEnded(message: String?) {
                log("live updates ended: ${message ?: "finished"}")
                if (authorization.value.blocksLocation) {
                    log("location ${authorization.value.name.lowercase()}: no restart")
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
        // `drop(1)`: a StateFlow replays its current value; only a *change* to granted restarts.
        launch {
            authorization.drop(1).collect { status ->
                if (status != LocationAuthorization.WhenInUse) return@collect
                restart?.cancel()
                attempt = 0
                log("location granted: restart now")
                updates.start(sink) // the forwarder stops any previous stream itself (`LocationForwarder.start`)
            }
        }
        awaitClose {
            restart?.cancel()
            updates.stop()
        }
    }

    /** `CoreLocationSource.swift:100` — Core Location marks an invalid value as negative: it becomes null (Swift D114). */
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
            // Recorded only, read by no rule yet (adapters/04); the fixture writes them as sAcc/course/cAcc/vAcc.
            speedAccuracy = valid(speedAccuracy),
            course = valid(course),
            courseAccuracy = valid(courseAccuracy),
            verticalAccuracy = valid(verticalAccuracy),
        )
    }

    companion object {
        /** D101: 2 · 4 · 8 · 16 · 30 s, the last value repeats. */
        val backoff: List<Duration> = listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds)
    }
}
