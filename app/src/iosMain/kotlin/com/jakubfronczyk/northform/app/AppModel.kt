package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.engine.run.CrashChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The composition root's view of runs (`App/Sources/AppModel.swift`): which run the screens show, and the
 * two app-level facts about it — one at a time, and a closed run is kept until its last write is done
 * (D112). Everything a run *does* lives in [ActiveRun]; this class only wires and holds. Pure Kotlin over
 * the ports: the same class drives a replay run in the simulator, a live run on the phone, and the tests.
 */
class AppModel(private val deps: AppDeps, private val scope: CoroutineScope) {
    private val _activeRun = MutableStateFlow<ActiveRun?>(null)
    /** What the screens show: the run flow while non-null, Today otherwise. */
    val activeRun: StateFlow<ActiveRun?> = _activeRun.asStateFlow()

    private val _unfinished = MutableStateFlow<UnfinishedRun?>(null)
    /** A recording a crash left behind (R28): the prompt is up while non-null, until the user chooses. */
    val unfinished: StateFlow<UnfinishedRun?> = _unfinished.asStateFlow()

    /** A run is being opened (permission prompts): Start does nothing until it is (`:27`). */
    private var opening = false
    /** Runs whose screen closed after start: kept until their last write is done (D112). */
    private val finishing = HashMap<RecordingId, ActiveRun>()

    /** `AppModel.swift:49` — one run at a time. */
    fun startRun() = openRun { ActiveRun.open(deps, scope) }

    /** The one guard for both ways a run opens: `opening` is set before the launch, so a second tap is caught (`AppModel.swift:50-53`). */
    private fun openRun(open: suspend () -> ActiveRun) {
        if (_activeRun.value != null || opening) return
        opening = true
        scope.launch {
            try { _activeRun.value = open() } finally { opening = false }
        }
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

    /** The R28 prompt's answer. The prompt stays up until the store confirms, except for Continue, which starts the run at once. */
    fun resolveUnfinished(choice: CrashChoice) {
        val run = _unfinished.value ?: return
        val id = run.id.value.take(8)
        when (choice) {
            CrashChoice.Continue -> openRun {
                ActiveRun.resume(deps, scope, run).also { _unfinished.value = null; deps.log("run $id crash: continued") }
            }
            // Save: the recording ends where the store last heard from it and goes to `computing`, where a normally closed
            // run sits too (`closeRun`); engine/04's summary path picks it up from there.
            CrashChoice.Save -> settleUnfinished(id, "saved → computing", "save") {
                if (run.facts.state == RecordingState.Active) deps.store.stop(run.id, run.facts.savedUntil)
                if (run.facts.state != RecordingState.Computing) deps.store.beginComputing(run.id)
            }
            CrashChoice.Delete -> settleUnfinished(id, "deleted", "delete") { deps.store.delete(run.id) }
        }
    }

    /** Save/Delete: the store call, then the prompt goes away and it is logged — or it stays up and the failure is logged. */
    private fun settleUnfinished(id: String, done: String, verb: String, call: suspend () -> Unit) {
        scope.launch {
            runCatching { call() }
                .onSuccess { _unfinished.value = null; deps.log("run $id crash: $done") }
                .onFailure { deps.log("run $id crash: $verb failed: $it") }
        }
    }

    /** `AppModel.swift:106` — every run that is still writing saves what it has (D94). */
    fun appWentToBackground() {
        val at = deps.wallClock.now()
        for (run in listOfNotNull(_activeRun.value) + finishing.values) scope.launch { run.backgrounded(at) }
    }

    /** `AppModel.swift:127` — hides the run screen at once; the run itself ends on its own terms (D116). */
    fun closeRun() {
        val run = _activeRun.value ?: return
        _activeRun.value = null
        deps.clearAlerts()
        finishing[run.id] = run
        scope.launch {
            deps.log("run ${run.id.value.take(8)} closed: ${run.close()}")
            finishing.remove(run.id)
            deps.log("session stop")
        }
    }
}
