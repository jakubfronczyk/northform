package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.replay.ReplayMeta
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.ports.ProfileStore
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.engine.store.InMemoryProfileStore
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.SavedRecording
import kotlinx.coroutines.CompletableDeferred
import com.jakubfronczyk.northform.core.ports.SavedRecordings
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.engine.store.StoredSavedRecordings
import com.jakubfronczyk.northform.ui.ExportState
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
import kotlin.test.assertNotEquals
import kotlin.coroutines.cancellation.CancellationException
import com.jakubfronczyk.northform.engine.run.RunSummary
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
        val model = appOver(InMemoryRecordingStore(), saved)

        model.refreshSaved(); runCurrent() // the launch read, still waiting
        model.refreshSaved(); runCurrent() // a later read (after a Save) answers first
        slow.complete(emptyList()); runCurrent()

        assertEquals(listOf(RecordingId("new")), model.saved.value.recordings.map { it.recording.id })
    }

    private fun savedRun(id: String) = SavedRecording(
        Recording(id = RecordingId(id), type = RecordingType.Run, state = RecordingState.Saved, startUtc = straightRun().meta.start, tzOffset = 0),
        Headline(1000.0, 300.seconds, 300.seconds, null),
    )

    private fun TestScope.modelOver(saved: SavedRecordings) = appOver(InMemoryRecordingStore(), saved)

    /**
     * The history-screen wiring: a replay app over the in-memory [store], reading [saved] (the store's own by default) and
     * writing through [port] (the store, or a wrapper that fails or waits).
     */
    private fun TestScope.appOver(
        store: InMemoryRecordingStore,
        saved: SavedRecordings = StoredSavedRecordings(store, store) {},
        port: RecordingStore = store,
        profiles: ProfileStore = InMemoryProfileStore(),
        exportBackup: suspend () -> String = { "northform-backup.json" },
        makeTap: (ReplayMeta, String) -> RecordingTap? = { _, _ -> null },
        newRecordingId: () -> String = { "00000000-0000-0000-0000-00000000000a" },
    ) = AppModel(
        AppDeps(
            source = RunSource.Replay { straightRun() }, store = port, reliability = store, wallClock = virtualWallClock(straightRun().meta.start),
            alerts = RecordedAlerts(), profileStore = profiles, exportBackup = exportBackup, makeTap = makeTap, newRecordingId = newRecordingId, saved = saved,
        ),
        backgroundScope,
    )

    /** history-screen/01: one list for Today and History, so it holds every saved recording, not Today's three. */
    @Test
    fun the_saved_list_holds_every_saved_recording() = runTest {
        val five = (1..5).map { savedRun("r$it") }
        val model = modelOver(object : SavedRecordings { override suspend fun latest(n: Int) = five.take(n) })

        model.refreshSaved(); runCurrent()

        assertEquals(SavedList.Loaded(five), model.saved.value)
    }

    /** PR #3 review: a cancelled read is not a failed one: History must not say "Couldn't load history" for it. */
    @Test
    fun a_cancelled_read_is_not_published_as_failed() = runTest {
        val model = modelOver(object : SavedRecordings { override suspend fun latest(n: Int): List<SavedRecording> = throw CancellationException("gone") })

        model.refreshSaved(); runCurrent()

        assertEquals(SavedList.Loading, model.saved.value)
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

    /** profile-settings/02: Export backup is busy while the file is written, then says where it went. */
    @Test
    fun export_is_busy_while_writing_then_saved_and_a_second_tap_meanwhile_does_nothing() = runTest {
        val written = CompletableDeferred<String>()
        var calls = 0
        val model = appOver(InMemoryRecordingStore(), exportBackup = { calls++; written.await() })

        model.exportBackup(); runCurrent()
        assertEquals(ExportState.Busy, model.export.value)
        model.exportBackup(); runCurrent()
        written.complete("northform-backup-20261028-1200.json"); runCurrent()

        assertEquals(ExportState.Saved("northform-backup-20261028-1200.json"), model.export.value)
        assertEquals(1, calls)
    }

    @Test
    fun a_failed_export_says_why_and_can_be_tried_again() = runTest {
        var fail = true
        val model = appOver(InMemoryRecordingStore(), exportBackup = { if (fail) error("disk full") else "b.json" })

        model.exportBackup(); runCurrent()
        assertEquals(ExportState.Failed("disk full"), model.export.value)
        fail = false
        model.exportBackup(); runCurrent()
        assertEquals(ExportState.Saved("b.json"), model.export.value)
    }

    /** profile-settings/01: the stored profile, read at launch, is what a new run computes with. */
    @Test
    fun a_new_run_computes_with_the_stored_profile() = runTest {
        val store = InMemoryRecordingStore()
        val mine = AppDeps.sampleProfile.copy(hrMax = 199, hrRest = 48)
        val model = appOver(store, profiles = InMemoryProfileStore(mine))
        model.checkUnfinished(); runCurrent()
        assertEquals(mine, model.profile.value)

        val id = saveReplayRun(model)

        assertTrue(store.records.metrics(id).all { it.profile == mine }, "the run's metrics carry the stored profile")
    }

    /** first-launch-setup/01: with no stored profile the app needs Setup, unknown until the launch read lands. */
    @Test
    fun no_stored_profile_needs_setup_and_saving_step_one_ends_it() = runTest {
        val profiles = InMemoryProfileStore()
        val model = appOver(InMemoryRecordingStore(), profiles = profiles)
        assertNull(model.needsSetup.value, "unknown before the read: the root shows nothing yet")
        runCurrent()
        assertEquals(true, model.needsSetup.value)

        val mine = AppDeps.sampleProfile.copy(hrMax = 195)
        model.saveProfile(mine); runCurrent()

        assertEquals(false, model.needsSetup.value)
        assertEquals(mine, profiles.profile())
    }

    /** first-launch-setup: the profile saved on step 1 is what the first run computes with: its config, replay header and metrics. */
    @Test
    fun a_run_after_setup_uses_that_profile() = runTest {
        val store = InMemoryRecordingStore()
        val metas = ArrayList<ReplayMeta>()
        val model = appOver(store, makeTap = { meta, _ -> metas += meta; null })
        model.checkUnfinished(); runCurrent()
        assertEquals(true, model.needsSetup.value)
        val mine = AppDeps.sampleProfile.copy(hrMax = 201, hrRest = 52)

        model.saveProfile(mine); runCurrent()
        val id = saveReplayRun(model)

        assertEquals(listOf(201 to 52), metas.map { it.hrMax to it.hrRest }, "the run's config and replay header")
        assertTrue(store.records.metrics(id).all { it.profile == mine }, "the run's metrics")
    }

    /** first-launch-setup story 12: saved runs from before Setup existed, but no profile: Setup once, the runs untouched. */
    @Test
    fun recordings_but_no_profile_still_need_setup() = runTest {
        val model = modelOver(object : SavedRecordings { override suspend fun latest(n: Int) = listOf(savedRun("a")) })
        model.checkUnfinished(); runCurrent()

        assertEquals(true, model.needsSetup.value)
        assertEquals(listOf(RecordingId("a")), model.saved.value.recordings.map { it.recording.id })
    }

    @Test
    fun a_stored_profile_never_needs_setup() = runTest {
        val model = appOver(InMemoryRecordingStore(), profiles = InMemoryProfileStore(AppDeps.sampleProfile))
        runCurrent()
        assertEquals(false, model.needsSetup.value)
    }

    /** A read that fails can't tell; Setup would overwrite a profile that may exist, so the app opens as usual. */
    @Test
    fun a_failed_profile_read_does_not_force_setup() = runTest {
        val failing = object : ProfileStore by InMemoryProfileStore() { override suspend fun profile(): Profile? = error("locked") }
        val model = appOver(InMemoryRecordingStore(), profiles = failing)
        runCurrent()
        assertEquals(false, model.needsSetup.value)
    }

    /** profile-settings review: a run started before the launch read of the profile lands still computes with the stored one. */
    @Test
    fun a_run_started_before_the_profile_is_read_still_uses_the_stored_profile() = runTest {
        val mine = AppDeps.sampleProfile.copy(hrMax = 199, hrRest = 48)
        val metas = ArrayList<ReplayMeta>()
        val model = appOver(InMemoryRecordingStore(), profiles = InMemoryProfileStore(mine), makeTap = { meta, _ -> metas += meta; null })

        model.startRun(); runCurrent() // no checkUnfinished first: Run tapped as the app opens

        // The run's config (its live zones, its replay header) carries the stored profile, not the fallback.
        assertEquals(listOf(199 to 48), metas.map { it.hrMax to it.hrRest })
    }

    /** profile-settings review: the launch read never overwrites an edit saved before it landed. */
    @Test
    fun the_launch_read_does_not_overwrite_an_earlier_save() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stored = InMemoryProfileStore(AppDeps.sampleProfile)
        // The read sees the store as it was when it started, and answers late.
        val slow = object : ProfileStore by stored { override suspend fun profile(): Profile? { val then = stored.profile(); gate.await(); return then } }
        val model = appOver(InMemoryRecordingStore(), profiles = slow)
        model.checkUnfinished(); runCurrent() // the read waits
        val edited = AppDeps.sampleProfile.copy(weightKg = 70.0)

        model.saveProfile(edited); runCurrent()
        gate.complete(Unit); runCurrent()

        assertEquals(edited, model.profile.value)
        assertEquals(edited, stored.profile())
    }

    /** profile-settings review: a save the store refuses is said, not swallowed (Settings shows it). */
    @Test
    fun a_failed_profile_save_is_reported() = runTest {
        val refusing = object : ProfileStore by InMemoryProfileStore() { override suspend fun saveProfile(profile: Profile) = error("disk full") }
        val model = appOver(InMemoryRecordingStore(), profiles = refusing)

        model.saveProfile(AppDeps.sampleProfile.copy(weightKg = 70.0)); runCurrent()

        assertEquals("disk full", model.profileSaveError.value)
    }

    /** E15: an edit after a run was saved leaves its reopened Summary on the profile it was saved with. */
    @Test
    fun a_profile_edit_leaves_a_reopened_saved_run_on_its_stored_profile() = runTest {
        val store = InMemoryRecordingStore()
        val model = appOver(store)
        model.checkUnfinished(); runCurrent()
        val id = saveReplayRun(model)

        model.saveProfile(AppDeps.sampleProfile.copy(hrMax = 200)); runCurrent()
        model.openRecording(id); runCurrent()

        assertEquals(AppDeps.sampleProfile, assertIs<SummarySession.State.Ready>(assertNotNull(model.summary.value).state.value).summary.profile)
    }

    /** profile-settings/03: Save in the profile editor stores it, and the next run computes with it. */
    @Test
    fun a_saved_profile_reaches_the_store_and_the_next_run() = runTest {
        val store = InMemoryRecordingStore()
        val profiles = InMemoryProfileStore()
        val model = appOver(store, profiles = profiles)
        model.checkUnfinished(); runCurrent()
        val edited = AppDeps.sampleProfile.copy(weightKg = 70.5, hrMax = 192)

        model.saveProfile(edited); runCurrent()

        assertEquals(edited, profiles.profile())
        assertEquals(edited, model.profile.value)
        val id = saveReplayRun(model)
        assertTrue(store.records.metrics(id).all { it.profile == edited })
    }

    /** profile-settings/01 (SU6): "Update" on the new-max card saves the new HRmax for the next runs. */
    @Test
    fun accepting_a_new_max_saves_it_to_the_profile() = runTest {
        val store = InMemoryRecordingStore()
        val profiles = InMemoryProfileStore(AppDeps.sampleProfile.copy(hrMax = 130)) // the replay climbs past 130 from about 70 s
        val model = appOver(store, profiles = profiles)
        model.checkUnfinished(); runCurrent()
        model.startRun(); runCurrent()
        val run = assertNotNull(model.activeRun.value)
        run.session.tap(UserAction.Start)
        advanceTimeBy(126.seconds); run.session.tap(UserAction.Stop); runCurrent()
        advanceTimeBy(61.seconds); runCurrent()
        model.closeRun(); advanceTimeBy(1.seconds); runCurrent()
        val suggested = assertNotNull(assertIs<SummarySession.State.Ready>(assertNotNull(model.summary.value).state.value).summary.hrMaxSuggestion)

        model.acceptHrMax(); runCurrent()

        assertEquals(AppDeps.sampleProfile.copy(hrMax = suggested), profiles.profile(), "only HRmax changes")
        assertEquals(suggested, model.profile.value.hrMax)
    }

    /** SU6 is not offered on a reopened run: Update there changes nothing. */
    @Test
    fun update_on_a_reopened_run_saves_nothing() = runTest {
        val store = InMemoryRecordingStore()
        val profiles = InMemoryProfileStore(AppDeps.sampleProfile.copy(hrMax = 130))
        val model = appOver(store, profiles = profiles)
        model.checkUnfinished(); runCurrent()
        val id = saveReplayRun(model)
        model.openRecording(id); runCurrent()

        model.acceptHrMax(); runCurrent()

        assertEquals(130, assertNotNull(profiles.profile()).hrMax)
    }

    /** history-screen/03: a saved recording reopens read-only, computed with the profile it was saved with (D24). */
    @Test
    fun a_saved_recording_reopens_in_review_with_its_stored_profile_and_edits() = runTest {
        val store = InMemoryRecordingStore()
        val id = saveReplayRun(AppModel(replayDeps(straightRun(), store, RecordedAlerts()), backgroundScope))
        val raised = AppDeps.sampleProfile.copy(hrMax = AppDeps.sampleProfile.hrMax + 10) // the profile changed since
        val model = appOver(store, profiles = InMemoryProfileStore(raised))
        model.checkUnfinished(); runCurrent()

        model.openRecording(id); runCurrent()

        val session = assertNotNull(model.summary.value)
        assertTrue(session.review)
        val ready = assertIs<SummarySession.State.Ready>(session.state.value)
        assertEquals(AppDeps.sampleProfile, ready.summary.profile, "the stored profile, not today's")
        val withToday = RunSummary.compute(assertNotNull(store.records.load(id)), raised)
        assertNotEquals(withToday.trimp, ready.summary.trimp, "what the runner sees differs: TRIMP as it was, not under today's HRmax")
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
        val model = appOver(store, saved)
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
        val model = appOver(store, saved)
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
        var next = 0
        val model = appOver(store, port = slow, newRecordingId = { "00000000-0000-0000-0000-00000000000${next++}" })
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
