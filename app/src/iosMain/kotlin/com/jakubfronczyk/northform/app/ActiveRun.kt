package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.LiveActivityFeed
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.RunState
import com.jakubfronczyk.northform.engine.run.resumed
import com.jakubfronczyk.northform.replay.RecordingTap
import com.jakubfronczyk.northform.replay.ReplayMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One run, end to end (`AppModel.swift:12-160` without the screen bookkeeping): opens the source,
 * builds the session, keeps it running, records it in debug builds, and tears it all down in the one
 * right order. The composition root holds the current one; the screens see only [session].
 *
 * Why a module of its own: every Phase 1 lane (persistence, metrics, Live Activity) adds a side
 * observer or a finish step to a run. They land here, behind `open`/`close`, not in the root.
 */
class ActiveRun private constructor(
    val id: RecordingId,
    val session: RunSessionHandle,
    /** `run()`'s job; the run is over when it completes. Tests wait on it. */
    internal val runJob: Job,
    private val tap: RecordingTap?,
    private val tasks: BackgroundTasks,
    private val release: () -> Unit,
) {
    /**
     * The app went to the background (`AppModel.swift:106`): save what is buffered (D94) and flush the
     * tap. The save runs inside a background task (persistence/02) held for as long as the session's
     * `backgrounded` waits for the store to answer the batch — or until iOS expires the task. When
     * there is nothing to save (before start, after the run's last write) that is no time at all.
     */
    suspend fun backgrounded(at: Instant, held: HeldTask = holdFlushTask()) {
        held.run {
            tap?.write()
            session.backgrounded(at)
        }
    }

    /** Begins the flush's background task now, on the caller's thread (the `DidEnterBackground` observer), before any work. */
    fun holdFlushTask(): HeldTask = tasks.hold(FLUSH_TASK)

    /**
     * `AppModel.swift:127` — the run screen closed. The session decides what that means (D116): before
     * start the run is dropped, after start it keeps going until its last write. Returns when it is over.
     */
    suspend fun close(): EndResult {
        tap?.write()
        val result = session.end()
        runJob.join()
        release()
        return result
    }

    companion object {
        /** A new recording of [type] (today-screen/04: the Run or Walk tile): a fresh gate, a new id, the debug tap. */
        suspend fun open(deps: AppDeps, scope: CoroutineScope, type: RecordingType = RecordingType.Run): ActiveRun {
            val config = RunConfig(RecordingId(deps.newRecordingId()), type, deps.profile, deps.tzOffset())
            val start = deps.wallClock.now()
            val tap = deps.makeTap(
                ReplayMeta(config.type, start, config.profile.hrMax, config.profile.hrRest, config.profile.sex),
                fileName(start, config.type),
            )
            return start(deps, scope, RunState(config), tap)
        }

        /**
         * R28 "Continue": the same run, started where the store left it plus the gap until now
         * (`RunReducer.resumed`), through the same session. No tap: a fixture of a run's tail has no
         * start to replay from, and the original file already holds the part before the crash.
         */
        suspend fun resume(deps: AppDeps, scope: CoroutineScope, unfinished: UnfinishedRun, at: Instant): ActiveRun =
            start(deps, scope, RunReducer.resumed(unfinished.rebuilt, at = at), tap = null)

        /** Opens the source, starts the session from [initial] and the trace, then lets the source go (`onRunning`). */
        private suspend fun start(deps: AppDeps, scope: CoroutineScope, initial: RunState, tap: RecordingTap?): ActiveRun {
            val config = initial.config
            val observer: ((RunInput) -> Unit)? = tap?.let { it::observe } // bound reference: the tap owns the RunInput → fixture mapping (replay/01)
            val inputs = deps.source.open(deps.wallClock, scope)
            val session = RecordingSession(config, inputs.sensor, inputs.location, deps.store, deps.alerts, deps.wallClock, observer, initial = initial)
            deps.log("session start ${config.type.wire}" + if (initial.progress.startedAt != null) " (resumed, R28)" else "")
            val runJob = scope.launch { session.run() }
            val logJob = scope.launch { logProgress(config.recordingId, session, deps.log) }
            // D11: the lock screen follows the same snapshots as the run screen, at the feed's cadence.
            val feed = LiveActivityFeed()
            val liveActivityJob = scope.launch {
                session.snapshots.collect { s -> feed.on(s, deps.wallClock.now())?.let(deps.liveActivity) }
            }
            inputs.onRunning() // after `run()` is queued: the session subscribes before anything is emitted
            return ActiveRun(config.recordingId, session, runJob, tap, deps.backgroundTasks) {
                logJob.cancel()
                liveActivityJob.cancel()
                feed.close()?.let(deps.liveActivity) // a run closed mid-way (D116) must not leave the lock screen counting
                inputs.release()
            }
        }

        /** The one background task this app holds: the flush when the app leaves the foreground. */
        const val FLUSH_TASK = "com.jakubfronczyk.northform.flush"

        /** `AppModel.swift:148` — `Recordings/<start>-<type>.jsonl`, colons replaced so the name is a valid file name. */
        fun fileName(start: Instant, type: RecordingType) =
            Instant.fromEpochSeconds(start.epochSeconds).toString().replace(":", "-") + "-${type.wire}.jsonl"


        /** The run's trace lines (`RecordingSession.swift:143-198`, DEVTEST): every phase change, and a summary once a minute. */
        private suspend fun logProgress(id: RecordingId, session: RunSessionHandle, log: (String) -> Unit) {
            var phase: RunPhase? = null
            var lastSummary: Instant? = null
            session.snapshots.collect { s ->
                if (s.phase != phase) {
                    log("run ${id.value.take(8)} phase ${phase?.name() ?: "-"} → ${s.phase.name()}")
                    phase = s.phase
                }
                val now = s.startedAt?.plus(s.totalTime) ?: return@collect
                if (lastSummary?.let { now - it < 60.seconds } == true) return@collect
                lastSummary = now
                log("minute: total ${s.totalTime.inWholeSeconds} s, distance ${s.distance.toInt()} m, hr ${s.hrNow ?: "-"} avg ${s.hrAvg ?: "-"}, pace ${s.currentPace?.toInt() ?: "-"}, hrLost ${s.hrLost}, gpsLost ${s.gpsLost}")
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
