package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.replay.ReplayLocation
import com.jakubfronczyk.northform.replay.ReplaySensor
import com.jakubfronczyk.northform.replay.ReplayTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the active run and coordinates its lifecycle (`App/Sources/AppModel.swift`). The run task is
 * owned here, not by the run screen, so closing the screen never ends the recording (D112); the
 * session decides what closing means (D116). Pure Kotlin over the ports: the same class drives a
 * replay run in the simulator, a live run on the phone, and the simulator test.
 */
class AppModel(
    private val deps: AppDeps,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    /** `AppModel.swift:12` — `run()` is owned here; `playback` is the replay timeline in the simulator. */
    class ActiveRun(val id: RecordingId, val session: RecordingSession, val runJob: Job, val playback: Job?, internal val logJob: Job)

    private val _activeRun = MutableStateFlow<ActiveRun?>(null)
    /** What the screens show: the run flow while non-null, Today otherwise. */
    val activeRun: StateFlow<ActiveRun?> = _activeRun.asStateFlow()

    /** A run is being opened (permission prompts): Start does nothing until it is (`:27`). */
    private var opening = false
    /** Runs whose screen closed after start: kept until their last write is done (D112). */
    private val finishing = HashMap<RecordingId, ActiveRun>()

    /** `AppModel.swift:49` — one run at a time. */
    fun startRun() {
        if (_activeRun.value != null || opening) return
        opening = true
        scope.launch {
            try { openRun() } finally { opening = false }
        }
    }

    private suspend fun openRun() {
        val session: RecordingSession
        var playback: Job? = null
        val config = RunConfig(RecordingId(deps.newRecordingId()), RecordingType.Run, deps.profile, deps.tzOffset())
        when (val source = deps.source) {
            is RunSource.Live -> {
                // Asked here, before the run screen opens, so the prompt never covers a running clock (`:66-74`).
                source.requestPermissions()
                session = RecordingSession(config, source.sensor, source.location, deps.store, deps.alerts, deps.wallClock)
                source.keepAlive.hold()
            }
            is RunSource.Replay -> {
                val timeline = ReplayTimeline(source.script(), deps.wallClock)
                session = RecordingSession(config, ReplaySensor(timeline), ReplayLocation(timeline), deps.store, deps.alerts, deps.wallClock)
                playback = scope.launch { timeline.play() }
            }
        }
        val runJob = scope.launch { session.run() }
        val logJob = scope.launch {
            session.snapshots.map { it.phase }.distinctUntilChanged().collect { log("run ${config.recordingId.value.take(8)} phase $it") }
        }
        _activeRun.value = ActiveRun(config.recordingId, session, runJob, playback, logJob)
    }

    /** `AppModel.swift:116` — the app is going to the background: save what the run has buffered now, stamped at this moment. */
    fun appWentToBackground() {
        val at = deps.wallClock.now()
        for (run in listOfNotNull(_activeRun.value) + finishing.values) {
            scope.launch { run.session.backgrounded(at) }
        }
    }

    /**
     * `AppModel.swift:127` — hides the run screen at once. The session decides what closing means
     * (D116): before start it drops the run, after start it keeps going until its last write. Either
     * way `run()` returns by itself; the run is kept until then.
     */
    fun closeRun() {
        val run = _activeRun.value ?: return
        _activeRun.value = null
        deps.clearAlerts()
        finishing[run.id] = run
        scope.launch {
            log("run ${run.id.value.take(8)} closed: ${run.session.end()}")
            run.runJob.join()
            run.playback?.cancel()
            run.logJob.cancel()
            (deps.source as? RunSource.Live)?.keepAlive?.release()
            finishing.remove(run.id)
            log("run ${run.id.value.take(8)} finished its last write")
        }
    }
}
