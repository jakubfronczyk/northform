package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.SavedRecording
import kotlinx.coroutines.CompletableDeferred
import com.jakubfronczyk.northform.core.ports.SavedRecordings
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.engine.store.StoredSavedRecordings
import com.jakubfronczyk.northform.ui.SavedList
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.ports.Alert
import com.jakubfronczyk.northform.core.ports.AlertPlayer
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.ReliabilityStore
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.engine.run.SummaryEdits
import com.jakubfronczyk.northform.engine.run.SummarySession
import com.jakubfronczyk.northform.engine.store.InMemoryRecordingStore
import com.jakubfronczyk.northform.persistence.SqlRecordingStore
import com.jakubfronczyk.northform.persistence.nativeDriver
import co.touchlab.sqliter.DatabaseFileContext
import com.jakubfronczyk.northform.replay.Fixture
import com.jakubfronczyk.northform.replay.ReplayScript
import com.jakubfronczyk.northform.testing.RecordedAlerts
import com.jakubfronczyk.northform.testing.tap
import com.jakubfronczyk.northform.testing.virtualWallClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * The simulator proof of the end-to-end flow: the composition root drives a REPLAY run through the
 * whole flow, Gate → Countdown → Active → Cool-down → Done, on a virtual clock, with the phase log the
 * app writes. Same `AppModel` the phone uses; only the ports differ (replay here, live there).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelReplayTest {

    /** The test runs inside the simulator sandbox with no bundle: the fixture text is generated into the test sources by Gradle. */
    private fun straightRun(): ReplayScript = Fixture.parse(BundledFixtureText.STRAIGHT_RUN_3MIN)

    /** The simulator wiring: a replay source on the test's virtual clock, an in-memory store. */
    private fun TestScope.replayDeps(script: ReplayScript, store: RecordingStore, alerts: AlertPlayer, reliability: ReliabilityStore = store as ReliabilityStore, log: (String) -> Unit = {}) = AppDeps(
        source = RunSource.Replay { script },
        store = store,
        reliability = reliability,
        wallClock = virtualWallClock(script.meta.start),
        alerts = alerts,
        log = log,
        newRecordingId = { "00000000-0000-0000-0000-00000000000a" },
        saved = (store as? SavedRows)?.let { StoredSavedRecordings(it, store, log) } ?: SavedRecordings.None,
    )

    @Test
    fun a_replay_run_goes_from_gate_to_summary() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val alerts = RecordedAlerts()
        val phases = ArrayList<String>()
        val model = AppModel(replayDeps(script, store, alerts) { line -> phases += line }, backgroundScope)

        assertNull(model.activeRun.value)
        model.startRun()
        runCurrent()
        val run = assertNotNull(model.activeRun.value, "the run screen opens on the replay session")
        assertEquals(RunPhase.Gate, run.session.snapshots.value.phase)

        run.session.tap(UserAction.Start) // the Start tap
        advanceTimeBy(6.seconds); runCurrent()
        assertEquals(RunPhase.Active, run.session.snapshots.value.phase, "countdown over")
        advanceTimeBy(120.seconds); runCurrent()
        val live = run.session.snapshots.value
        assertTrue(live.distance > 200, "distance climbs on replay GPS: ${live.distance} m")
        assertNotNull(live.hrNow, "live heart rate from the replay sensor")

        run.session.tap(UserAction.Stop) // hold-to-stop
        runCurrent()
        assertTrue(run.session.snapshots.value.phase is RunPhase.Cooldown)
        advanceTimeBy(61.seconds); runCurrent()
        assertEquals(RunPhase.Done, run.session.snapshots.value.phase, "the Summary screen")

        model.closeRun() // the flow closes itself at Done
        advanceTimeBy(1.seconds); runCurrent()
        assertNull(model.activeRun.value)
        assertEquals(RecordingState.Computing, assertNotNull(store.records.load(run.id)).recording.state)
        val summary = assertNotNull(model.summary.value, "the Summary opens for the finished run")
        val ready = assertIs<SummarySession.State.Ready>(summary.state.value)
        assertTrue(ready.summary.distance > 200, "the same run the screen showed")
        assertNotNull(store.records.reliability(run.id), "the reliability row is written before the Summary shows")

        model.editSummary(SummaryEdits(tag = RunTag.Easy, rpe = 4, notes = null))
        model.saveSummary() // Save tapped
        runCurrent()
        assertNull(model.summary.value, "back to Today")
        val saved = assertNotNull(store.records.load(run.id)).recording
        assertEquals(RecordingState.Saved, saved.state)
        assertEquals(RunTag.Easy, saved.tag)
        assertEquals(listOf(run.id), model.saved.value.recordings.map { it.recording.id }, "today-screen/01: Today re-reads once the Summary closes")
        assertEquals(5, alerts.played.count { it == Alert.CountdownTick })
        assertTrue(phases.any { "→ gate" in it } && phases.any { "→ active" in it } && phases.any { "→ done" in it }, "lifecycle log: $phases")
    }

    /** today-screen/04: the Walk tile's start records a walk, from the first row the store holds; the gate says so. */
    @Test
    fun the_walk_start_creates_a_recording_of_type_walk() = runTest {
        val store = InMemoryRecordingStore()
        val model = AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope)

        model.startRun(RecordingType.Walk)
        runCurrent()
        val run = assertNotNull(model.activeRun.value)
        assertEquals(RecordingType.Walk, run.session.snapshots.value.type, "the gate shows its walk copy")
        run.session.tap(UserAction.Start)
        advanceTimeBy(6.seconds); runCurrent()

        assertEquals(RecordingType.Walk, assertNotNull(store.records.load(run.id)).recording.type)
    }

    /** today-screen/01: Today reads the saved list at launch. */
    @Test
    fun the_saved_list_is_read_at_launch() = runTest {
        val store = InMemoryRecordingStore()
        val first = AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope)
        first.startRun(); runCurrent()
        val run = assertNotNull(first.activeRun.value)
        run.session.tap(UserAction.Start)
        advanceTimeBy(66.seconds); run.session.tap(UserAction.Stop); runCurrent()
        advanceTimeBy(61.seconds); runCurrent()
        first.closeRun(); advanceTimeBy(1.seconds); runCurrent()
        first.saveSummary(); runCurrent()

        val relaunched = AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope)
        assertEquals(SavedList.Loading, relaunched.saved.value, "history-screen/01: nothing read yet")
        relaunched.checkUnfinished(); runCurrent()
        assertEquals(listOf(run.id), relaunched.saved.value.recordings.map { it.recording.id })
    }

    /** today-screen code review: a slow read that finishes after a newer one never puts the older list back. */
    @Test
    fun an_older_saved_read_finishing_last_does_not_overwrite_a_newer_one() = runTest {
        val slow = CompletableDeferred<List<SavedRecording>>()
        val newer = listOf(savedRun("new"))
        var calls = 0
        val saved = object : SavedRecordings {
            override suspend fun latest(n: Int) = if (calls++ == 0) slow.await() else newer
        }
        val store = InMemoryRecordingStore()
        val model = AppModel(AppDeps(source = RunSource.Replay { straightRun() }, store = store, reliability = store, wallClock = virtualWallClock(straightRun().meta.start), alerts = RecordedAlerts(), saved = saved), backgroundScope)

        model.refreshSaved(); runCurrent() // the launch read, still waiting
        model.refreshSaved(); runCurrent() // a later read (after a Save) answers first
        slow.complete(emptyList()); runCurrent()

        assertEquals(listOf(RecordingId("new")), model.saved.value.recordings.map { it.recording.id })
    }

    private fun savedRun(id: String) = SavedRecording(
        Recording(id = RecordingId(id), type = RecordingType.Run, state = RecordingState.Saved, startUtc = straightRun().meta.start, tzOffset = 0),
        Headline(1000.0, 300.seconds, 300.seconds, null),
    )

    private fun TestScope.modelOver(saved: SavedRecordings): AppModel {
        val store = InMemoryRecordingStore()
        return AppModel(AppDeps(source = RunSource.Replay { straightRun() }, store = store, reliability = store, wallClock = virtualWallClock(straightRun().meta.start), alerts = RecordedAlerts(), saved = saved), backgroundScope)
    }

    /** history-screen/01: one list for Today and History, so it holds every saved recording, not Today's three. */
    @Test
    fun the_saved_list_holds_every_saved_recording() = runTest {
        val five = (1..5).map { savedRun("r$it") }
        val model = modelOver(object : SavedRecordings { override suspend fun latest(n: Int) = five.take(n) })

        model.refreshSaved(); runCurrent()

        assertEquals(SavedList.Loaded(five), model.saved.value)
    }

    /** history-screen/01: a failed read is flagged for History and keeps the last list for Today. */
    @Test
    fun a_failed_read_keeps_the_last_list_and_says_so() = runTest {
        var fail = false
        val model = modelOver(object : SavedRecordings {
            override suspend fun latest(n: Int) = if (fail) error("disk") else listOf(savedRun("a"))
        })
        model.refreshSaved(); runCurrent()

        fail = true
        model.refreshSaved(); runCurrent()

        val failed = assertIs<SavedList.Failed>(model.saved.value)
        assertEquals(listOf(RecordingId("a")), failed.recordings.map { it.recording.id })
    }

    /** A replay run started, stopped, cooled down and saved with tag Easy, RPE 4; returns its id. */
    private suspend fun TestScope.saveReplayRun(model: AppModel): RecordingId {
        model.startRun(); runCurrent()
        val run = assertNotNull(model.activeRun.value)
        run.session.tap(UserAction.Start)
        advanceTimeBy(66.seconds); run.session.tap(UserAction.Stop); runCurrent()
        advanceTimeBy(61.seconds); runCurrent()
        model.closeRun(); advanceTimeBy(1.seconds); runCurrent()
        model.editSummary(SummaryEdits(tag = RunTag.Easy, rpe = 4, notes = "flat")); model.saveSummary(); runCurrent()
        return run.id
    }

    /** history-screen/03: a saved recording reopens read-only, computed with the profile it was saved with (D24). */
    @Test
    fun a_saved_recording_reopens_in_review_with_its_stored_profile_and_edits() = runTest {
        val store = InMemoryRecordingStore()
        val id = saveReplayRun(AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope))
        val raised = AppDeps.sampleProfile.copy(hrMax = AppDeps.sampleProfile.hrMax + 10) // the profile changed since
        val model = AppModel(AppDeps(source = RunSource.Replay { straightRun() }, store = store, reliability = store, wallClock = virtualWallClock(straightRun().meta.start), alerts = RecordedAlerts(), profile = raised, saved = StoredSavedRecordings(store, store) {}), backgroundScope)
        model.checkUnfinished(); runCurrent()

        model.openRecording(id); runCurrent()

        val session = assertNotNull(model.summary.value)
        assertTrue(session.review)
        val ready = assertIs<SummarySession.State.Ready>(session.state.value)
        assertEquals(AppDeps.sampleProfile, ready.summary.profile, "the stored profile, not today's")
        assertEquals(SummaryEdits(RunTag.Easy, 4, "flat"), session.edits.value)
    }

    @Test
    fun back_closes_the_review_and_changes_nothing_stored() = runTest {
        val store = InMemoryRecordingStore()
        val model = AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope)
        val id = saveReplayRun(model)
        val before = store.records.load(id)

        model.openRecording(id); runCurrent()
        model.closeSummary()

        assertNull(model.summary.value)
        assertEquals(before, store.records.load(id))
        assertEquals(listOf(id), model.saved.value.recordings.map { it.recording.id })
    }

    @Test
    fun delete_in_review_closes_it_and_the_recording_leaves_the_list() = runTest {
        val store = InMemoryRecordingStore()
        val model = AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope)
        val id = saveReplayRun(model)

        model.openRecording(id); runCurrent()
        model.deleteSummary(); runCurrent()

        assertNull(model.summary.value)
        assertNull(store.records.load(id))
        assertEquals(emptyList(), model.saved.value.recordings)
    }

    /** history-screen/03: the deleted row goes at once, so a re-read that fails cannot leave it on the list to tap. */
    @Test
    fun a_deleted_recording_leaves_the_list_even_when_the_re_read_fails() = runTest {
        val store = InMemoryRecordingStore()
        val real = StoredSavedRecordings(store, store) {}
        var fail = false
        val saved = object : SavedRecordings { override suspend fun latest(n: Int) = if (fail) error("disk") else real.latest(n) }
        val model = AppModel(replayDeps(straightRun(), store, RecordedAlerts()).let { d ->
            AppDeps(source = d.source, store = store, reliability = store, wallClock = d.wallClock, alerts = d.alerts, newRecordingId = d.newRecordingId, saved = saved)
        }, backgroundScope)
        val id = saveReplayRun(model)
        model.openRecording(id); runCurrent()

        fail = true
        model.deleteSummary(); runCurrent()

        val failed = assertIs<SavedList.Failed>(model.saved.value)
        assertEquals(emptyList(), failed.recordings)
    }

    /** history code review: a read that queried before a Delete and answers after it must not put the row back. */
    @Test
    fun a_read_from_before_the_delete_never_brings_the_deleted_row_back() = runTest {
        val store = InMemoryRecordingStore()
        val real = StoredSavedRecordings(store, store) {}
        val stale = CompletableDeferred<List<SavedRecording>>()
        var mode = "real"
        val saved = object : SavedRecordings {
            override suspend fun latest(n: Int) = when (mode) { "stale" -> stale.await(); "fail" -> error("disk"); else -> real.latest(n) }
        }
        val d = replayDeps(straightRun(), store, RecordedAlerts())
        val model = AppModel(AppDeps(source = d.source, store = store, reliability = store, wallClock = d.wallClock, alerts = d.alerts, newRecordingId = d.newRecordingId, saved = saved), backgroundScope)
        val id = saveReplayRun(model)
        val withRow = model.saved.value.recordings
        model.openRecording(id); runCurrent()
        mode = "stale"; model.refreshSaved(); runCurrent() // in flight, it saw the row

        mode = "fail"
        model.deleteSummary(); runCurrent() // the re-read after the delete fails
        stale.complete(withRow); runCurrent() // the old read answers last

        assertEquals(emptyList(), model.saved.value.recordings)
    }

    /** history code review: Back while a Delete is at the store, then another recording opened: the Delete must not close it. */
    @Test
    fun a_delete_finishing_after_back_does_not_close_the_next_review() = runTest {
        val store = InMemoryRecordingStore()
        val gate = CompletableDeferred<Unit>()
        val slow = object : RecordingStore by store {
            override suspend fun delete(id: RecordingId) { gate.await(); store.delete(id) }
        }
        val d = replayDeps(straightRun(), store, RecordedAlerts())
        var next = 0
        val model = AppModel(AppDeps(source = d.source, store = slow, reliability = store, wallClock = d.wallClock, alerts = d.alerts, newRecordingId = { "00000000-0000-0000-0000-00000000000${next++}" }, saved = StoredSavedRecordings(store, store) {}), backgroundScope)
        val first = saveReplayRun(model)
        val second = saveReplayRun(model)

        model.openRecording(first); runCurrent()
        model.deleteSummary(); runCurrent() // waiting at the store
        model.closeSummary()
        model.openRecording(second); runCurrent()
        gate.complete(Unit); runCurrent()

        assertEquals(second, model.summary.value?.recordingId)
    }

    /** code review a.3: a recording a crash left `active` still goes to the crash prompt, and no Summary opens. */
    @Test
    fun an_active_recording_found_at_launch_opens_the_crash_prompt_not_the_summary() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val first = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
        first.startRun(); runCurrent()
        val run = assertNotNull(first.activeRun.value)
        run.session.tap(UserAction.Start); advanceTimeBy(60.seconds); runCurrent()
        assertEquals(RecordingState.Active, assertNotNull(store.records.load(run.id)).recording.state, "the 'crash' happens mid-run")

        val second = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
        second.checkUnfinished(); runCurrent()

        assertNotNull(second.unfinished.value, "the R28 prompt")
        assertNull(second.summary.value)
    }

    /** summary-screen/05: a recording a crash left in `computing` opens the Summary at launch, never the crash prompt. */
    @Test
    fun a_computing_recording_found_at_launch_opens_the_summary_not_the_crash_prompt() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val first = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
        first.startRun(); runCurrent()
        val run = assertNotNull(first.activeRun.value)
        run.session.tap(UserAction.Start); advanceTimeBy(126.seconds); runCurrent()
        run.session.tap(UserAction.Stop); advanceTimeBy(61.seconds); runCurrent()
        first.closeRun(); advanceTimeBy(1.seconds); runCurrent()
        assertEquals(RecordingState.Computing, assertNotNull(store.records.load(run.id)).recording.state, "the 'crash' happens here, Summary never confirmed")

        val second = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope) // the app reopens
        second.checkUnfinished(); runCurrent()

        assertNull(second.unfinished.value, "no crash prompt for a computing recording")
        val summary = assertNotNull(second.summary.value, "the Summary opens directly")
        assertIs<SummarySession.State.Ready>(summary.state.value)
        assertEquals(run.id, summary.recordingId)
    }

    /**
     * persistence/01: the same replay run on the store the phone boots with — `SqlRecordingStore` over
     * the on-disk `nativeDriver()` (schema created on first open, synchronous=FULL), inside the
     * simulator sandbox. The file is deleted first, so a rerun never meets an old recording, and after.
     */
    @Test
    fun a_replay_run_is_stored_by_the_sqldelight_store_the_phone_uses() = runTest {
        val script = straightRun()
        val name = "replay-test.db"
        DatabaseFileContext.deleteDatabase(name)
        val driver = nativeDriver(name)
        try {
            val store = SqlRecordingStore(driver, io = EmptyCoroutineContext)
            val model = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
            model.startRun()
            runCurrent()
            val run = assertNotNull(model.activeRun.value)
            run.session.tap(UserAction.Start)
            advanceTimeBy(126.seconds); runCurrent()
            run.session.tap(UserAction.Stop)
            advanceTimeBy(61.seconds); runCurrent()
            assertEquals(RunPhase.Done, run.session.snapshots.value.phase)
            model.closeRun()
            advanceTimeBy(1.seconds); runCurrent()

            val stored = assertNotNull(store.load(run.id), "the run is in SQLite")
            assertEquals(RecordingState.Computing, stored.recording.state)
            assertTrue(stored.hr.size > 100 && stored.fixes.size > 100, "${stored.hr.size} hr, ${stored.fixes.size} fixes")
            assertEquals(stored.recording, store.unfinished(), "a crash here would find it (R28)")
            assertNotNull(store.reliability(run.id), "the reliability row is in SQLite too")

            assertNotNull(model.summary.value, "the Summary is up")
            model.deleteSummary() // bin, then the sheet's red button
            runCurrent()
            assertNull(model.summary.value, "back to Today")
            assertNull(store.load(run.id), "gone from SQLite")
        } finally {
            driver.close()
            DatabaseFileContext.deleteDatabase(name)
        }
    }

    @Test
    fun closing_at_the_gate_discards_and_leaves_no_recording() = runTest {
        val script = straightRun()
        val store = InMemoryRecordingStore()
        val model = AppModel(replayDeps(script, store, RecordedAlerts()), backgroundScope)
        model.startRun()
        runCurrent()
        val run = assertNotNull(model.activeRun.value)
        model.closeRun()
        advanceTimeBy(1.seconds); runCurrent()
        assertNull(store.records.load(run.id))
        assertTrue(run.runJob.isCompleted, "run() returned after the discard")
    }
}
