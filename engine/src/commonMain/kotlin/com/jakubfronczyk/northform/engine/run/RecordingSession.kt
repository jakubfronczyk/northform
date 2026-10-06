package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one recording live (`Engine/RecordingSession.swift`, D5): feeds sensor, GPS, ticks and taps
 * into [RunReducer] and carries out its effects.
 *
 * A single-owner mailbox: every producer sends a [RunInput] into one unbounded `Channel`; ONE
 * coroutine owns the [RunState]. Store effects go to a writer coroutine that runs them strictly in
 * order and reports back by SENDING `Persisted`/`PersistFailed` into the same inbox — never by
 * calling a method that mutates state, which is why no `Mutex` exists here (it is not reentrant).
 * Alerts have their own queue so a vibration never waits behind a write.
 *
 * Walking-skeleton step 5 finishes this (end(), handle, the D112/D116 lifetime rules); this is the
 * shape the engine port needs to compile and be exercised.
 */
class RecordingSession(
    val config: RunConfig,
    private val sensor: HeartRateSensor,
    private val location: LocationProvider,
    private val store: RecordingStore,
    private val alerts: AlertPlayer,
    val wallClock: WallClock,
) {
    private val inbox = Channel<RunInput>(Channel.UNLIMITED)
    private val writes = Channel<RunEffect.Store>(Channel.UNLIMITED)
    private val alertQueue = Channel<RunEffect.PlayAlert>(Channel.UNLIMITED)
    private val _snapshots = MutableStateFlow(RunState(config).snapshot)
    private val _routes = MutableStateFlow(Route())

    /** Conflatable views (StateFlow drops equal repeats): the screen only needs the latest. */
    val snapshots: StateFlow<RunSnapshot> = _snapshots.asStateFlow()
    val routes: StateFlow<Route> = _routes.asStateFlow()

    /** A tap, stamped by the caller with the run's clock (time is data, D100). */
    suspend fun send(action: UserAction, at: kotlin.time.Instant = wallClock.now()) = inbox.send(RunInput.User(action, at))

    suspend fun backgrounded(at: kotlin.time.Instant = wallClock.now()) = inbox.send(RunInput.Backgrounded(at))

    /** Runs until `BeginComputing` is confirmed (the writer ends) or cancellation tears the producers down. */
    suspend fun run(): Unit = coroutineScope {
        launch { sensor.heartRate().collect { inbox.send(RunInput.Hr(it)) } }
        launch { sensor.states().collect { inbox.send(RunInput.Sensor(it.state, it.at)) } }
        launch { location.fixes().collect { inbox.send(RunInput.Fix(it)) } }
        launch { while (true) { delay(1.seconds); inbox.send(RunInput.Tick(wallClock.now())) } }
        launch { for (a in alertQueue) alerts.play(a.alert) }
        val writer = launch { writeLoop() }

        launch {
            var state = RunState(config)
            for (input in inbox) {
                val (next, effects) = RunReducer.reduce(state, input)
                state = next
                _snapshots.value = next.snapshot
                for (e in effects) when (e) {
                    is RunEffect.Store -> writes.send(e)
                    is RunEffect.PlayAlert -> alertQueue.send(e)
                    is RunEffect.Route -> _routes.value = _routes.value + e.point
                }
            }
        }
        writer.join()
        coroutineContext.cancelChildren()
    }

    private suspend fun writeLoop() {
        for (effect in writes) when (effect) {
            is RunEffect.Create -> store.create(effect.recording)
            is RunEffect.Persist -> try {
                store.flush(effect.batch)
                inbox.send(RunInput.Persisted(effect.seq))
            } catch (e: Exception) {
                inbox.send(RunInput.PersistFailed(effect.seq))
            }
            is RunEffect.Stop -> store.stop(config.recordingId, effect.at)
            RunEffect.BeginComputing -> {
                store.beginComputing(config.recordingId)
                writes.close() // the last store effect: the run is over
            }
        }
    }
}
