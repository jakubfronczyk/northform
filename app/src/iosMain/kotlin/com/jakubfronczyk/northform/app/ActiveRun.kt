package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.EndResult
import com.jakubfronczyk.northform.engine.run.RecordingSession
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.engine.run.RunInput
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
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
    private val release: () -> Unit,
) {
    /** The app went to the background (`AppModel.swift:106`): save what is buffered (D94) and flush the tap. */
    suspend fun backgrounded(at: Instant) {
        session.backgrounded(at)
        tap?.write()
    }

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
        /** Opens the source, starts the session and the trace, then lets the source go (`onRunning`). */
        suspend fun open(deps: AppDeps, scope: CoroutineScope): ActiveRun {
            val config = RunConfig(RecordingId(deps.newRecordingId()), RecordingType.Run, deps.profile, deps.tzOffset())
            val start = deps.wallClock.now()
            val tap = deps.makeTap(
                ReplayMeta(config.type, start, config.profile.hrMax, config.profile.hrRest, config.profile.sex),
                fileName(start, config.type),
            )
            val observer: ((RunInput) -> Unit)? = tap?.let { it::observe } // bound reference: the tap owns the RunInput → fixture mapping (replay/01)
            val inputs = deps.source.open(deps.wallClock, scope)
            val session = RecordingSession(config, inputs.sensor, inputs.location, deps.store, deps.alerts, deps.wallClock, observer)
            deps.log("session start ${config.type.wire}")
            val runJob = scope.launch { session.run() }
            val logJob = scope.launch { logProgress(config.recordingId, session, deps.log) }
            inputs.onRunning() // after `run()` is queued: the session subscribes before anything is emitted
            return ActiveRun(config.recordingId, session, runJob, tap) {
                logJob.cancel()
                inputs.release()
            }
        }

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
