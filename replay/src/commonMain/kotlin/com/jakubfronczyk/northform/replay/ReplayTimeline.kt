package com.jakubfronczyk.northform.replay

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.SensorError
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.core.roundedToMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * `Replay/ReplayTimeline.swift` — plays one [ReplayScript] on one clock, so heart rate, GPS and
 * sensor state stay in the order the fixture has them. [ReplaySensor] and [ReplayLocation] both
 * read from it. Times are rebased: a sample's time is playback start (`wallClock.now()` when
 * [play] begins) plus its offset in the fixture, so replayed samples line up with live ticks.
 *
 * Swift `Broadcaster.events()` (deliver to whoever listens now) → `MutableSharedFlow` with a buffer;
 * `Broadcaster.state()` (replay the latest) → `MutableStateFlow`.
 */
class ReplayTimeline(val script: ReplayScript, val wallClock: WallClock) {

    private val _sensorStates = MutableStateFlow(SensorStateChange(initialSensorState(script), wallClock.now()))
    private val _heartRate = MutableSharedFlow<HrSample>(extraBufferCapacity = 1024)
    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 1024)
    private val _userActions = MutableSharedFlow<ReplayInput.User>(extraBufferCapacity = 64)
    private val playing = Mutex()

    val sensorStates = _sensorStates.asStateFlow()
    val heartRate = _heartRate.asSharedFlow()
    val fixes = _fixes.asSharedFlow()

    /** `ReplayTimeline.swift:46` — emits every input at its offset, then returns. Streams stay open afterwards. Plays once at a time. */
    suspend fun play() {
        check(playing.tryLock()) { "already playing" }
        try {
            val playbackStart = wallClock.now()
            var elapsed = 0L
            for (input in script.inputs) {
                val offset = input.t - script.meta.start
                val dueMs = offset.roundedToMillis()
                if (dueMs > elapsed) {
                    delay((dueMs - elapsed).milliseconds)
                    elapsed = dueMs
                }
                emit(input.at(playbackStart + offset))
            }
        } finally {
            playing.unlock()
        }
    }

    /** `ReplayTimeline.swift:74` — delivers one input now, as given (no rebasing). */
    suspend fun emit(input: ReplayInput) {
        when (input) {
            is ReplayInput.Hr -> _heartRate.emit(input.sample)
            is ReplayInput.Fix -> _fixes.emit(input.fix)
            is ReplayInput.Sensor -> _sensorStates.value = next(input.state, input.at, _sensorStates.value)
            is ReplayInput.User -> _userActions.emit(input)
        }
    }

    /** The fixture's user actions (pause, stop …), for driving a recording in tests. */
    fun userActions(): Flow<ReplayInput.User> = _userActions

    internal fun setSensorState(state: SensorState) {
        _sensorStates.value = next(state, wallClock.now(), _sensorStates.value)
    }

    companion object {
        /** The fixture format rule: connected, unless a `sensor` line at t = 0 says otherwise (`ReplayTimeline.swift:33`). */
        fun initialSensorState(script: ReplayScript): SensorState =
            script.inputs.filterIsInstance<ReplayInput.Sensor>().firstOrNull { it.t == script.meta.start }?.state
                ?: SensorState.Connected(battery = null)

        /**
         * `Core/Model/Sensor.swift:43` — the change to publish after `previous`: a battery-only update
         * while connected keeps the time the connection began; anything else starts now.
         */
        fun next(state: SensorState, at: Instant, previous: SensorStateChange): SensorStateChange =
            if (state is SensorState.Connected && previous.state is SensorState.Connected) SensorStateChange(state, previous.at)
            else SensorStateChange(state, at)
    }
}

/** `Replay/ReplaySensor.swift:5` — `HeartRateSensor` backed by a timeline. Same stream rules as the Bluetooth sensor. */
class ReplaySensor(private val timeline: ReplayTimeline) : HeartRateSensor {
    override fun states(): Flow<SensorStateChange> = timeline.sensorStates

    override fun search(): Flow<DiscoveredSensor> = flow { emit(DiscoveredSensor(sensorId, "Replay", -40)) }

    /** Always succeeds at once: there is no radio to wait for. */
    override suspend fun connect(id: SensorId) = timeline.setSensorState(SensorState.Connected(battery = null))

    override suspend fun disconnect() = timeline.setSensorState(SensorState.Disconnected(DisconnectReason.User))

    /** Live samples; ends with [SensorError.Unauthorized] when the fixture withdraws permission, as the real sensor does (`ReplayTimeline.swift:83`). */
    override fun heartRate(): Flow<HrSample> = channelFlow {
        launch {
            timeline.sensorStates.first { it.state == SensorState.Unauthorized }
            throw SensorError.Unauthorized
        }
        timeline.heartRate.collect { send(it) }
    }

    companion object {
        val sensorId = SensorId("replay")
    }
}

/** `Replay/ReplaySensor.swift:40` — `LocationProvider` backed by a timeline. */
class ReplayLocation(private val timeline: ReplayTimeline) : LocationProvider {
    override fun fixes(): Flow<LocationFix> = timeline.fixes
}
