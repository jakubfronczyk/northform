package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.ports.FlushBatch
import com.jakubfronczyk.northform.engine.run.CrashChoice
import com.jakubfronczyk.northform.engine.run.CrashPrompt
import com.jakubfronczyk.northform.engine.run.crashMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The composition root's view of runs (`App/Sources/AppModel.swift`): which run the screens show, and the
 * two app-level facts about it — one at a time, and a closed run is kept until its last write is done
 * (D112). Everything a run *does* lives in [ActiveRun]; this class only wires and holds. Pure Kotlin over
 * the ports: the same class drives a replay run in the simulator, a live run on the phone, and the tests.
 */
@OptIn(ExperimentalAtomicApi::class)
class AppModel(private val deps: AppDeps, private val scope: CoroutineScope) {
    private val _activeRun = MutableStateFlow<ActiveRun?>(null)
    /** What the screens show: the run flow while non-null, Today otherwise. */
    val activeRun: StateFlow<ActiveRun?> = _activeRun.asStateFlow()

    private val _unfinished = MutableStateFlow<UnfinishedRun?>(null)
    /** A recording a crash left behind (R28): the prompt is up while non-null, until the user chooses. */
    val unfinished: StateFlow<UnfinishedRun?> = _unfinished.asStateFlow()

    // Thread safety: the public calls come from the main thread, the launched work runs on the scope's dispatcher
    // (Dispatchers.Default in the app). Both pieces of state are therefore lock-free and non-suspending — an atomic
    // flag and an immutable map swapped by CAS (`update {}`) — rather than a Mutex: `appWentToBackground` must stay
    // synchronous (it begins iOS background tasks before returning), so it cannot suspend on a lock.

    /** A run is being opened (permission prompts): Start does nothing until it is (`:27`). */
    private val opening = AtomicBoolean(false)
    /** An R28 answer is being settled (Save/Delete store calls, or Continue opening): another tap does nothing until it is. */
    private val answering = AtomicBoolean(false)
    /** Runs whose screen closed after start: kept until their last write is done (D112). Immutable snapshots, swapped atomically. */
    private val finishing = MutableStateFlow<Map<RecordingId, ActiveRun>>(emptyMap())

    /** `AppModel.swift:49` — one run at a time; never while the R28 prompt is waiting for its answer (it owns the screen). */
    fun startRun() {
        if (_unfinished.value != null) return
        openRun { ActiveRun.open(deps, scope) }
    }

    /**
     * The one guard for both ways a run opens: `opening` is claimed (compare-and-set) before the launch, so a second tap
     * is caught (`AppModel.swift:50-53`). [open] may decline with `null`. Returns whether it launched.
     */
    private fun openRun(open: suspend () -> ActiveRun?): Boolean {
        if (_activeRun.value != null || !opening.compareAndSet(false, true)) return false
        scope.launch {
            try { open()?.let { _activeRun.value = it } } finally { opening.store(false) }
        }
        return true
    }

    /**
     * D9 step 4, at boot: is there a recording a crash left unfinished? Read and rebuilt off the boot
     * path; nothing resumes until [resolveUnfinished] (R28: the user chooses).
     */
    fun checkUnfinished() {
        scope.launch {
            _unfinished.value = runCatching { UnfinishedRun.find(deps) }
                .onFailure { deps.log("unfinished check failed: $it") }
                .getOrNull()
        }
    }

    /**
     * The R28 prompt's answer, as [CrashPrompt.resolve] decides it at the tap (D30). The prompt stays up until the store
     * confirms, except for Continue, which starts the run at once. One answer at a time: `answering` is claimed
     * (compare-and-set, like `opening`) before anything runs, so a second tap while one is being settled is ignored —
     * two Save taps before the store answered used to write two `CrashRecovered`.
     */
    fun resolveUnfinished(choice: CrashChoice) {
        val run = _unfinished.value ?: return
        val id = run.id.value.take(8)
        val resolution = CrashPrompt.resolve(run.facts, deps.wallClock.now(), choice)
        if (resolution is CrashPrompt.Resolution.Refused) {
            // A late tap on a Continue still drawn from an older render: the prompt stays up and redraws without it.
            deps.log("run $id crash: ${resolution.choice} refused (not offered after ${resolution.interruptedFor.inWholeMinutes} min)")
            return
        }
        if (!answering.compareAndSet(false, true)) return deps.log("run $id crash: $choice ignored (an answer is being settled)")
        when (resolution) {
            // Continue only resumes a recording the store still holds as `active`: one already stopped (a crash after
            // the stop, or a Save that got halfway) has nothing to resume, so the answer is saved instead.
            is CrashPrompt.Resolution.Continue -> {
                val launched = openRun {
                    try {
                        val stored = runCatching { storedState(run) }.getOrElse { deps.log("run $id crash: continue failed: $it"); return@openRun null }
                        if (stored != RecordingState.Active) {
                            deps.log("run $id crash: continue refused (stored $stored), saving")
                            runCatching { save(run, CrashPrompt.Resolution.Save(crashMark(resolution.at), endAt = run.facts.savedUntil)) }
                                .onSuccess { _unfinished.value = null; deps.log("run $id crash: saved → computing") }
                                .onFailure { deps.log("run $id crash: save failed: $it") }
                            return@openRun null
                        }
                        ActiveRun.resume(deps, scope, run, resolution.at).also { _unfinished.value = null; deps.log("run $id crash: continued") }
                    } finally {
                        answering.store(false)
                    }
                }
                if (!launched) answering.store(false)
            }
            is CrashPrompt.Resolution.Save -> settleUnfinished(id, "saved → computing", "save") { save(run, resolution) }
            CrashPrompt.Resolution.Delete -> settleUnfinished(id, "deleted", "delete") { deps.store.delete(run.id) }
            is CrashPrompt.Resolution.Refused -> error("handled above")
        }
    }

    /**
     * Save: the recording ends at [answer]'s `endAt` (`savedUntil`, D30: never at reopen) and goes to `computing`, where a
     * normally closed run sits too (`closeRun`); engine/04's summary path picks it up from there. Each attempt re-reads the
     * stored data and does only the steps left, so a retry after a half-done Save never repeats a step.
     *
     * D32: while still `active`, the crash mark is flushed first, with the heartbeat it already has so "saved until" does
     * not move. It is stamped no earlier than that heartbeat (a wall clock moved backwards still lands it at or after it),
     * so a mark at or after `savedUntil` can only be this Save's own (a resumed run's mark is followed by its first flush's
     * later heartbeat): a retry finds it and skips it.
     */
    private suspend fun save(run: UnfinishedRun, answer: CrashPrompt.Resolution.Save) {
        val data = deps.store.load(run.id) ?: return // gone: nothing left to save
        val stored = data.recording.state
        if (stored == RecordingState.Active) {
            val savedUntil = answer.endAt
            val marked = data.events.any { it.kind == EventKind.CrashRecovered && it.t >= savedUntil }
            if (!marked) {
                val mark = crashMark(maxOf(answer.mark.t, savedUntil))
                deps.store.flush(FlushBatch(run.id, events = listOf(mark), persistedAt = savedUntil))
            }
            deps.store.stop(run.id, savedUntil)
        }
        if (stored == RecordingState.Active || stored == RecordingState.Stopping) deps.store.beginComputing(run.id)
    }

    /** The recording's state as the store holds it now — not as it was at boot. */
    private suspend fun storedState(run: UnfinishedRun): RecordingState? = deps.store.load(run.id)?.recording?.state

    /** Save/Delete: the store call, then the prompt goes away and it is logged — or it stays up and the failure is logged. Releases `answering`. */
    private fun settleUnfinished(id: String, done: String, verb: String, call: suspend () -> Unit) {
        scope.launch {
            try {
                runCatching { call() }
                    .onSuccess { _unfinished.value = null; deps.log("run $id crash: $done") }
                    .onFailure { deps.log("run $id crash: $verb failed: $it") }
            } finally {
                answering.store(false)
            }
        }
    }

    /**
     * `AppModel.swift:106` — every run that is still writing saves what it has (D94). Called on the main thread from the
     * `DidEnterBackground` observer: each run's background task is begun here, before returning, so iOS holds the
     * process before any of the flush (or the tap's write) is even scheduled.
     */
    fun appWentToBackground() {
        val at = deps.wallClock.now()
        for (run in listOfNotNull(_activeRun.value) + finishing.value.values) {
            val held = run.holdFlushTask()
            scope.launch { run.backgrounded(at, held) }
        }
    }

    /** `AppModel.swift:127` — hides the run screen at once; the run itself ends on its own terms (D116). */
    fun closeRun() {
        val run = _activeRun.value ?: return
        _activeRun.value = null
        deps.clearAlerts()
        finishing.update { it + (run.id to run) }
        scope.launch {
            deps.log("run ${run.id.value.take(8)} closed: ${run.close()}")
            finishing.update { it - run.id }
            deps.log("session stop")
        }
    }
}
