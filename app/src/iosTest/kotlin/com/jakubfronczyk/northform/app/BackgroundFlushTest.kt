package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * persistence/02: the background flush runs inside one `UIApplication` background task — begun when
 * the app leaves, ended exactly once when the store confirms the batch, or when iOS expires the task.
 * The task seam is a fake; the store is gated so the test decides when a flush lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundFlushTest {
    private val script = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    /** Records begins and ends; keeps the expiration handler so a test can play iOS. */
    private class FakeTasks : BackgroundTasks {
        val events = ArrayList<String>()
        var expire: (() -> Unit)? = null
        override fun begin(name: String, onExpire: () -> Unit): Long { events += "begin $name"; expire = onExpire; return 7 }
        override fun end(task: Long) { events += "end $task" }
    }

    /** Every flush waits at the gate until [release]; the in-memory store does the writing. */
    private class GatedStore(val inner: InMemoryRecordingStore = InMemoryRecordingStore()) : RecordingStore by inner {
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun flush(batch: FlushBatch) {
            gate.await()
            inner.flush(batch)
        }
        fun close() { gate = CompletableDeferred() }
        fun release() { gate.complete(Unit) }
    }

    private fun TestScope.deps(store: RecordingStore, tasks: BackgroundTasks) = AppDeps(
        source = RunSource.Replay { script }, store = store, reliability = InMemoryRecordingStore() /* never reached: these runs stop before the Summary */, wallClock = virtualWallClock(script.meta.start), alerts = RecordedAlerts(),
        backgroundTasks = tasks, newRecordingId = { "00000000-0000-0000-0000-00000000000d" },
    )

    private suspend fun TestScope.activeRun(store: RecordingStore, tasks: BackgroundTasks): ActiveRun {
        val run = ActiveRun.open(deps(store, tasks), backgroundScope)
        runCurrent()
        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()
        assertEquals(RunPhase.Active, run.session.snapshots.value.phase)
        return run
    }

    @Test
    fun the_task_is_held_until_the_backgrounded_batch_is_confirmed_then_ended_once() = runTest {
        val tasks = FakeTasks()
        val store = GatedStore()
        val run = activeRun(store, tasks)
        advanceTimeBy(2.seconds); runCurrent()
        store.close()
        val at = run.session.wallClock.now()
        backgroundScope.launch { run.backgrounded(at) }
        runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}"), tasks.events, "begun, and held while the flush is in the store")

        store.release(); runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events, "ended once the batch landed")
        val data = assertNotNull(store.inner.records.load(run.id))
        assertEquals(true, data.lastPersistedAt!! >= at, "the batch asked for at $at is durable: heartbeat ${data.lastPersistedAt}")
    }

    @Test
    fun an_expired_task_is_ended_once_and_the_wait_let_go() = runTest {
        val tasks = FakeTasks()
        val store = GatedStore()
        val run = activeRun(store, tasks)
        store.close()
        backgroundScope.launch { run.backgrounded(run.session.wallClock.now()) }
        runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}"), tasks.events)

        assertNotNull(tasks.expire).invoke() // iOS: time is up
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events, "ended inside the expiration handler, before it returns (Apple's rule)")
        runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events, "the cancelled work ends nothing twice")
        store.release(); advanceTimeBy(10.seconds); runCurrent() // the store comes back later: the run keeps writing, no second end
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events)
        assertNotNull(store.inner.records.load(run.id)).lastPersistedAt
    }

    @Test
    fun going_to_the_background_begins_the_task_before_any_work_is_scheduled() = runTest {
        val tasks = FakeTasks()
        val store = GatedStore()
        val model = AppModel(deps(store, tasks), backgroundScope)
        model.startRun(); runCurrent()
        val run = assertNotNull(model.activeRun.value)
        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()
        store.close()

        model.appWentToBackground() // the main-thread observer: no runCurrent, nothing launched has run yet
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}"), tasks.events, "begun synchronously, in the observer")
        store.release(); runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events)
    }

    @Test
    fun before_start_nothing_is_held() = runTest {
        val tasks = FakeTasks()
        val store = GatedStore().apply { close() } // a held flush would stay held: none must be asked for
        val run = ActiveRun.open(deps(store, tasks), backgroundScope)
        runCurrent()
        backgroundScope.launch { run.backgrounded(run.session.wallClock.now()) }
        runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events, "no row exists at the gate: nothing to confirm, ended at once")
    }

    @Test
    fun a_failed_flush_lets_the_task_go() = runTest {
        val tasks = FakeTasks()
        val store = GatedStore()
        val run = activeRun(store, tasks)
        store.close()
        backgroundScope.launch { run.backgrounded(run.session.wallClock.now()) }
        runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}"), tasks.events)

        store.gate.completeExceptionally(IllegalStateException("disk full")); runCurrent()
        assertEquals(listOf("begin ${ActiveRun.FLUSH_TASK}", "end 7"), tasks.events, "answered, if not confirmed: the screen says not saving")
        assertEquals(true, run.session.notSaving.value)
    }
}
