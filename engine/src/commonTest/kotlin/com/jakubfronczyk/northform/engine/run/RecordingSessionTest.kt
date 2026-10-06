package com.jakubfronczyk.northform.engine.run

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.WallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The virtual-clock wiring the architect review asked for: ONE clock is both the coroutine
 * scheduler (drives `delay`) and the `WallClock` (stamps inputs), so `advanceTimeBy` moves both —
 * the Kotlin equivalent of the Swift tests' single `VirtualClock`.
 * https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-test/README.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionTest {

    private val start = Instant.fromEpochSeconds(1_800_000_000)

    private fun TestScope.virtualWallClock() = WallClock { start + testScheduler.currentTime.milliseconds }

    private class FakeSensor : HeartRateSensor {
        val hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
        val state = MutableStateFlow(SensorStateChange(SensorState.Connected(80), Instant.fromEpochSeconds(0)))
        override fun states(): Flow<SensorStateChange> = state
        override fun heartRate(): Flow<HrSample> = hr
        override fun search(): Flow<DiscoveredSensor> = emptyFlow()
        override suspend fun connect(id: SensorId) {}
        override suspend fun disconnect() {}
    }

    private class RecordingWriter : RunWriter {
        val persisted = mutableListOf<Pair<Int, FlushBatch>>()
        var stoppedAt: Instant? = null
        override suspend fun persist(seq: Int, batch: FlushBatch) { persisted += seq to batch }
        override suspend fun stop(at: Instant) { stoppedAt = at }
    }

    @Test
    fun samples_are_flushed_every_5s_and_the_buffer_clears_only_on_persisted() = runTest {
        val clock = virtualWallClock()
        val sensor = FakeSensor()
        val writer = RecordingWriter()
        val session = RecordingSession(sensor, writer, clock)
        val job = launch { session.run() }
        runCurrent() // let run() subscribe before the first emit (a SharedFlow drops emissions with no subscriber)

        session.send(UserAction.Start)
        advanceTimeBy(1.seconds)
        sensor.hr.emit(HrSample(clock.now(), 120))
        advanceTimeBy(1.seconds)
        sensor.hr.emit(HrSample(clock.now(), 122))
        advanceTimeBy(5.seconds) // tick ≥ 5 s after start → flush

        assertEquals(1, writer.persisted.size)
        assertEquals(listOf(120, 122), writer.persisted[0].second.hr.map { it.bpm })
        // Persisted(seq) travelled back through the inbox: buffer cleared, nothing in flight.
        assertEquals(null, session.snapshot.value.inFlight)
        assertEquals(emptyList(), session.snapshot.value.unconfirmedHr)

        job.cancel()
    }

    @Test
    fun hr_lost_after_5s_without_samples() = runTest {
        val clock = virtualWallClock()
        val sensor = FakeSensor()
        val session = RecordingSession(sensor, RecordingWriter(), clock)
        val job = launch { session.run() }
        runCurrent()
        session.send(UserAction.Start)
        runCurrent()
        sensor.hr.emit(HrSample(clock.now(), 130))
        advanceTimeBy(1.seconds)
        assertEquals(130, session.snapshot.value.hrNow)
        advanceTimeBy(5.seconds)
        assertEquals(true, session.snapshot.value.hrLost)
        job.cancel()
    }

    @Test
    fun stop_is_durable_before_the_final_batch_and_run_returns() = runTest {
        val clock = virtualWallClock()
        val sensor = FakeSensor()
        val writer = RecordingWriter()
        val session = RecordingSession(sensor, writer, clock)
        val job = launch { session.run() }
        runCurrent()
        session.send(UserAction.Start)
        advanceTimeBy(1.seconds)
        sensor.hr.emit(HrSample(clock.now(), 140))
        advanceTimeBy(1.seconds)
        session.send(UserAction.Stop)
        advanceTimeBy(1.seconds)

        assertEquals(start + 2.seconds, writer.stoppedAt)
        assertEquals(1, writer.persisted.size)
        job.join() // "tests never hang": run() ends after the final persist is confirmed
    }
}
