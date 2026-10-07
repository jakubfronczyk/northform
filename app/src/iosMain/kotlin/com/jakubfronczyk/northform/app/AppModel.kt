package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.replay.ReplayInput
import com.jakubfronczyk.northform.replay.ReplayLocation
import com.jakubfronczyk.northform.replay.ReplayMeta
import com.jakubfronczyk.northform.replay.ReplaySensor
import com.jakubfronczyk.northform.replay.ReplayTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Owns the active run and coordinates its lifecycle (`App/Sources/AppModel.swift`). The run task is
 * owned here, not by the run screen, so closing the screen never ends the recording (D112); the
 * session decides what closing means (D116). Pure Kotlin over the ports: the same class drives a
 * replay run in the simulator, a live run on the phone, and the simulator test.
 */
class AppModel(private val deps: AppDeps, private val scope: CoroutineScope) {
    /** `AppModel.swift:12` — `run()` is owned here; `playback` is the replay timeline in the simulator; `tap` records the run (debug). */
    class ActiveRun(val id: RecordingId, val session: RecordingSession, val runJob: Job, val playback: Job?, val tap: RecordingTap?, internal val logJob: Job)

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
        val config = RunConfig(RecordingId(deps.newRecordingId()), RecordingType.Run, deps.profile, deps.tzOffset())
        val start = deps.wallClock.now()
        val tap = deps.makeTap(
            ReplayMeta(config.type, start, config.profile.hrMax, config.profile.hrRest, config.profile.sex),
            fileName(start, config.type),
        )
        val observer: ((RunInput) -> Unit)? = tap?.let { t -> { input -> record(input, t) } }
        val session: RecordingSession
        var playback: Job? = null
        when (val source = deps.source) {
            is RunSource.Live -> {
                // Asked here, before the run screen opens, so the prompt never covers a running clock (`:66-74`).
                source.requestPermissions()
                session = RecordingSession(config, source.sensor, source.location, deps.store, deps.alerts, deps.wallClock, observer)
                source.keepAlive.hold()
            }
            is RunSource.Replay -> {
                val timeline = ReplayTimeline(source.script(), deps.wallClock)
                session = RecordingSession(config, ReplaySensor(timeline), ReplayLocation(timeline), deps.store, deps.alerts, deps.wallClock, observer)
                playback = scope.launch { timeline.play() }
            }
        }
        deps.log("session start ${config.type.wire}")
        val runJob = scope.launch { session.run() }
        val logJob = scope.launch { logProgress(config.recordingId, session) }
        _activeRun.value = ActiveRun(config.recordingId, session, runJob, playback, tap, logJob)
    }

    /** The run's trace lines (`RecordingSession.swift:143-198`, DEVTEST): every phase change, and a summary once a minute. */
    private suspend fun logProgress(id: RecordingId, session: RecordingSession) {
        var phase: RunPhase? = null
        var lastSummary: Instant? = null
        session.snapshots.collect { s ->
            if (s.phase != phase) {
                deps.log("run ${id.value.take(8)} phase ${phase?.name() ?: "-"} → ${s.phase.name()}")
                phase = s.phase
            }
            val now = s.startedAt?.plus(s.totalTime) ?: return@collect
            if (lastSummary?.let { now - it < 60.seconds } == true) return@collect
            lastSummary = now
            deps.log("minute: total ${s.totalTime.inWholeSeconds} s, distance ${s.distance.toInt()} m, hr ${s.hrNow ?: "-"} avg ${s.hrAvg ?: "-"}, pace ${s.currentPace?.toInt() ?: "-"}, hrLost ${s.hrLost}, gpsLost ${s.gpsLost}")
        }
    }

    /** `AppModel.swift:116` — the app is going to the background: save what the run has buffered now, stamped at this moment. */
    fun appWentToBackground() {
        val at = deps.wallClock.now()
        for (run in listOfNotNull(_activeRun.value) + finishing.values) {
            scope.launch { run.session.backgrounded(at) }
            run.tap?.write()
        }
    }

    /**
     * `AppModel.swift:127` — hides the run screen at once. The session decides what closing means
     * (D116): before start it drops the run, after start it keeps going until its last write. Either
     * way `run()` returns by itself; the run is kept until then.
     */
    fun closeRun() {
        val run = _activeRun.value ?: return
        run.tap?.write()
        _activeRun.value = null
        deps.clearAlerts()
        finishing[run.id] = run
        scope.launch {
            deps.log("run ${run.id.value.take(8)} closed: ${run.session.end()}")
            run.runJob.join()
            run.playback?.cancel()
            run.logJob.cancel()
            (deps.source as? RunSource.Live)?.keepAlive?.release()
            finishing.remove(run.id)
            deps.log("session stop")
        }
    }

    companion object {
        /** `AppModel.swift:148` — `Recordings/<start>-<type>.jsonl`, colons replaced so the name is a valid file name. */
        fun fileName(start: Instant, type: RecordingType) =
            Instant.fromEpochSeconds(start.epochSeconds).toString().replace(":", "-") + "-${type.wire}.jsonl"

        /** `AppModel.swift:157` — what the reducer saw, as fixture lines. Ticks only drive the 30 s rewrite. */
        fun record(input: RunInput, tap: RecordingTap) {
            when (input) {
                is RunInput.Hr -> tap.record(ReplayInput.Hr(input.sample))
                is RunInput.Fix -> tap.record(ReplayInput.Fix(input.fix))
                is RunInput.Sensor -> tap.record(ReplayInput.Sensor(input.state, input.at))
                is RunInput.User -> tap.record(ReplayInput.User(input.action, input.at))
                is RunInput.Tick -> tap.tick(input.t)
                is RunInput.Backgrounded, is RunInput.Persisted, is RunInput.PersistFailed -> Unit
            }
        }

        private fun RunPhase.name() = when (this) {
            RunPhase.Gate -> "gate"
            is RunPhase.Countdown -> "countdown"
            RunPhase.Active -> "active"
            is RunPhase.Paused -> "paused"
            is RunPhase.Cooldown -> "cooldown"
            RunPhase.Done -> "done"
        }
    }
}
