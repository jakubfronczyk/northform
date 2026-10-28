package com.jakubfronczyk.northform.adapters.location

import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * D14 (adapters/05): the keep-alive is this adapter's, held for exactly as long as `fixes()` is collected — the only
 * time a run needs the process alive while locked is while it records GPS. Collections may overlap (a closed run
 * still finishing while the next one opens): the keep-alive is counted, held by the first and released by the last,
 * and the forwarder belongs to the newest — an older collection neither stops nor restarts it.
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
    /** Required: held while `fixes()` is collected (D14); `BackgroundActivityKeepAlive` on the phone, a fake in tests. */
    private val keepAlive: KeepAlive,
    private val log: (String) -> Unit = {},
) : LocationProvider {

    /** Guards what collections share: the forwarder, the keep-alive count and which stream is current. */
    private val shared = Mutex()
    private var holders = 0 // guarded by `shared`
    private var current = 0 // guarded by `shared`: the generation the forwarder is feeding now

    /**
     * What reaches the stream's one owner. Fixes and ends carry the generation of the sink they came through: the
     * forwarder reports `onEnded` for a stream it was told to stop (`LocationForwarder.start` stops the previous one),
     * and that end must not trigger a restart (it would cancel the healthy stream, which ends, which restarts, …).
     */
    private sealed interface Event {
        data class Fix(val generation: Int, val fix: LocationFix) : Event
        data class Ended(val generation: Int, val message: String?) : Event
        data class Retry(val token: Int) : Event
        data object Granted : Event
    }

    /**
     * A cold stream per call. One owner (adapters/05): the Swift forwarder calls the sink on the main actor and the
     * collector runs on its own dispatcher, so the sink only *posts* an [Event], and the backoff child only posts
     * [Event.Retry]; the loop below is the only place that reads `attempt` or starts and stops the forwarder.
     * Order per collection: hold the keep-alive, start GPS, …, stop GPS, release.
     */
    override fun fixes(): Flow<LocationFix> = channelFlow {
        val streamStart = wallClock.now()
        val events = Channel<Event>(Channel.UNLIMITED) // never blocks or drops a callback from Swift
        fun sink(generation: Int) = object : LocationSink {
            override fun onUpdate(location: CLLocation, stationary: Boolean) {
                val fix = location.toFix(stationary) ?: return
                if (fix.t < streamStart) return // cached fix from before this stream began (D92 rule)
                events.trySend(Event.Fix(generation, fix))
            }

            override fun onEnded(message: String?) {
                events.trySend(Event.Ended(generation, message))
            }
        }

        /** A fresh sink per start. A new collection takes the forwarder over; a restart happens only while it is still ours. */
        suspend fun start(mine: Int?, takeOver: Boolean): Int? = shared.withLock {
            if (!takeOver && current != mine) return@withLock null
            current += 1
            updates.start(sink(current))
            current
        }

        var attempt = 0
        var restart: Job? = null
        var retryToken = 0
        var pendingRetry: Int? = null
        var mine: Int? = null
        shared.withLock { if (holders++ == 0) keepAlive.hold() } // after the location answer (`RunSource.Live.open`), before the first fix
        try {
            mine = start(null, takeOver = true)
            // `drop(1)`: a StateFlow replays its current value; only a *change* to granted restarts.
            launch { authorization.drop(1).collect { if (it == LocationAuthorization.WhenInUse) events.send(Event.Granted) } }
            for (event in events) when (event) {
                is Event.Fix -> if (event.generation == mine) {
                    attempt = 0 // a fix proves the stream is healthy again
                    send(event.fix) // suspends while the collector is behind; `events` holds the rest
                }
                is Event.Ended -> {
                    if (event.generation != mine) continue // a stream we replaced ourselves
                    log("live updates ended: ${event.message ?: "finished"}")
                    if (shared.withLock { current != mine }) continue // a newer collection took the forwarder over
                    if (authorization.value.blocksLocation) {
                        log("location ${authorization.value.name.lowercase()}: no restart")
                        continue
                    }
                    val wait = backoff[minOf(attempt, backoff.lastIndex)]
                    attempt += 1
                    log("restart $attempt in ${wait.inWholeSeconds} s")
                    restart?.cancel()
                    val token = ++retryToken
                    pendingRetry = token
                    restart = launch {
                        delay(wait)
                        events.send(Event.Retry(token))
                    }
                }
                is Event.Retry -> {
                    if (event.token != pendingRetry) continue // superseded by a grant or a later end
                    pendingRetry = null
                    start(mine, takeOver = false)?.let { mine = it }
                }
                Event.Granted -> {
                    restart?.cancel()
                    pendingRetry = null
                    attempt = 0
                    mine = start(mine, takeOver = false) ?: continue // a newer collection owns GPS
                    log("location granted: restart now")
                }
            }
        } finally {
            restart?.cancel()
            withContext(NonCancellable) {
                shared.withLock {
                    if (mine != null && current == mine) { current += 1; updates.stop() } // only the newest stops GPS
                    if (--holders == 0) keepAlive.release()
                }
            }
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
