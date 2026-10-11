package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.LocationFix
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.SensorStateChange
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.core.ports.LocationProvider
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * ONE clock is both the coroutine scheduler (drives `delay`) and the `WallClock` (stamps inputs), so
 * `advanceTimeBy` moves both — the Kotlin form of the Swift tests' single `VirtualClock`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionTest {
    private val start = Instant.fromEpochSeconds(1_800_000_000)

    private class FakeSensor : HeartRateSensor {
        val hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
        val state = MutableStateFlow(SensorStateChange(SensorState.Connected(80), Instant.fromEpochSeconds(0)))
        override fun states(): Flow<SensorStateChange> = state
        override fun heartRate(): Flow<HrSample> = hr
        override fun search(): Flow<DiscoveredSensor> = emptyFlow()
        override suspend fun connect(id: SensorId) {}
        override suspend fun disconnect() {}
    }

    private object NoLocation : LocationProvider {
        override fun fixes(): Flow<LocationFix> = emptyFlow()
    }

    /** The in-memory store, with a switch to fail the next flush once (Kotlin interface delegation for the rest). */
    private class Store(val inner: InMemoryRecordingStore = InMemoryRecordingStore()) : RecordingStore by inner {
        var failNext = false
        /** The first row cannot be written (persistence/02): every later flush fails too, there is no recording. */
        var failCreate = false
        override suspend fun create(recording: Recording) {
            if (failCreate) error("disk full")
            inner.create(recording)
        }
        override suspend fun flush(batch: FlushBatch) {
            if (failNext) { failNext = false; error("disk full") }
            inner.flush(batch)
        }
        fun data() = inner.records.load(RunHarness.config.recordingId)!!
    }


    private fun TestScope.session(sensor: FakeSensor, store: Store, alerts: RecordedAlerts = RecordedAlerts()) =
        RecordingSession(RunHarness.config, sensor, NoLocation, store, alerts, virtualWallClock(start))

    /**
     * `run()` cancelled before its mailbox was ever dispatched: a child cancelled before it starts never
     * runs its body, so the seal must not live only in the mailbox. `backgrounded` and `end` return.
     */
    @Test
    fun run_cancelled_before_the_mailbox_starts_still_answers_backgrounded_and_end() = runTest {
        val session = session(FakeSensor(), Store())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { session.run() } // children launched, none dispatched yet
        job.cancel()
        runCurrent()
        withTimeout(5.seconds) {
            session.backgrounded(start + 1.seconds)
            assertEquals(EndResult.Discarded, session.end(), "sealed from the gate it never left")
        }
    }

    /** Crash resume (R28): a session started from a rebuilt state is already active and keeps the old total time. */
    @Test
    fun a_session_started_from_a_rebuilt_state_continues_the_run() = runTest {
        val sensor = FakeSensor()
        val store = Store()
        val startedAt = start - 10.minutes
        val resumed = RunState(
            RunHarness.config,
            progress = RunProgress(phase = RunPhase.Active, startedAt = startedAt),
            buffer = PersistBuffer(lastPersistAt = start),
        )
        val session = RecordingSession(RunHarness.config, sensor, NoLocation, store, RecordedAlerts(), virtualWallClock(start), initial = resumed)
        assertEquals(RunPhase.Active, session.snapshots.value.phase, "no gate, no countdown")
        val job = launch { session.run() }
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(RunPhase.Active, session.snapshots.value.phase)
        assertTrue(session.snapshots.value.totalTime >= 10.minutes, "total time carries over: ${session.snapshots.value.totalTime}")
        assertEquals(EndResult.Finishing, session.end(), "a resumed run was started, so closing finishes it")
        job.cancel()
    }

    @Test
    fun countdown_then_samples_flushed_every_5s_and_confirmed_through_the_inbox() = runTest {
        val sensor = FakeSensor()
        val store = Store()
        val alerts = RecordedAlerts()
        val session = session(sensor, store, alerts)
        val job = launch { session.run() }
        runCurrent()

        session.tap(UserAction.Start) // t = 0: countdown to 5 s
        advanceTimeBy(5.seconds); runCurrent() // ticks 1..5 → active at 5 s
        assertEquals(RunPhase.Active, session.snapshots.value.phase)
        assertEquals(5, alerts.played.count { it == Alert.CountdownTick })

        sensor.hr.emit(HrSample(session.wallClock.now(), 120))
        advanceTimeBy(1.seconds)
        sensor.hr.emit(HrSample(session.wallClock.now(), 122))
        advanceTimeBy(5.seconds); runCurrent() // the 5 s flush

        assertEquals(listOf(120, 122), store.data().hr.map { it.bpm })
        assertEquals(121, session.snapshots.value.hrAvg)
        job.cancel()
    }

    @Test
    fun a_failed_flush_is_retried_without_loss() = runTest {
        val sensor = FakeSensor()
        val store = Store()
        val session = session(sensor, store)
        val job = launch { session.run() }
        runCurrent()
        session.tap(UserAction.Start)
        advanceTimeBy(5.seconds); runCurrent()
        store.failNext = true
        sensor.hr.emit(HrSample(session.wallClock.now(), 130))
        assertFalse(session.notSaving.value)
        advanceTimeBy(5.seconds); runCurrent() // first flush fails → PersistFailed → buffer kept
        assertTrue(session.notSaving.value, "the screen may say not saving (persistence/02)")
        advanceTimeBy(5.seconds); runCurrent() // next flush carries it
        assertEquals(listOf(130), store.data().hr.map { it.bpm })
        assertFalse(session.notSaving.value, "saving again once a flush lands")
        job.cancel()
    }

    /** persistence/02: the first row fails ⇒ "not saving" at once, and it stays, because every flush fails on a missing recording. */
    @Test
    fun a_failed_create_shows_not_saving_from_the_start() = runTest {
        val sensor = FakeSensor()
        val store = Store().apply { failCreate = true }
        val session = session(sensor, store)
        val job = launch { session.run() }
        runCurrent()
        session.tap(UserAction.Start)
        advanceTimeBy(5.seconds); runCurrent() // countdown over: Create fails
        assertEquals(RunPhase.Active, session.snapshots.value.phase, "the run goes on (Swift only logged this)")
        assertTrue(session.notSaving.value)
        sensor.hr.emit(HrSample(session.wallClock.now(), 130))
        advanceTimeBy(10.seconds); runCurrent() // two flushes: NotFound, NotFound
        assertTrue(session.notSaving.value)
        assertEquals(null, store.inner.records.load(RunHarness.config.recordingId))
        job.cancel()
    }

    @Test
    fun stop_then_skip_cooldown_ends_the_run_after_begin_computing() = runTest {
        val sensor = FakeSensor()
        val store = Store()
        val session = session(sensor, store)
        val job = launch { session.run() }
        runCurrent()
        session.tap(UserAction.Start)
        advanceTimeBy(5.seconds); runCurrent()
        sensor.hr.emit(HrSample(session.wallClock.now(), 140))
        advanceTimeBy(2.seconds)
        session.tap(UserAction.Stop)
        runCurrent()
        session.tap(UserAction.SkipCooldown)
        advanceTimeBy(1.seconds); runCurrent()

        val data = store.data()
        assertEquals(RecordingState.Computing, data.recording.state)
        assertEquals(start + 7.seconds, data.recording.endUtc)
        assertTrue(job.isCompleted, "run() ends once BeginComputing is confirmed")
    }
}
