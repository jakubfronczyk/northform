package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
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

    /** A run is being opened (permission prompts): Start does nothing until it is (`:27`). */
    private var opening = false
    /** Runs whose screen closed after start: kept until their last write is done (D112). */
    private val finishing = HashMap<RecordingId, ActiveRun>()

    /** `AppModel.swift:49` — one run at a time. */
    fun startRun() {
        if (_activeRun.value != null || opening) return
        opening = true
        scope.launch {
            try { _activeRun.value = ActiveRun.open(deps, scope) } finally { opening = false }
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
