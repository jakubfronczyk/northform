package com.jakubfronczyk.northform.app

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.CrashFacts
import com.jakubfronczyk.northform.engine.run.RunConfig
import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunState
import com.jakubfronczyk.northform.engine.run.rebuild

/**
 * A recording a crash left in `active`/`stopping`/`computing` (R28), as found at launch: its stored data,
 * the run rebuilt from it up to the last heartbeat, and the facts the prompt shows. Found and rebuilt
 * only: nothing runs until the user chooses (D9), and the rebuild is the same fold Continue starts from.
 */
class UnfinishedRun(val data: RecordingData, val rebuilt: RunState, val facts: CrashFacts) {
    val id: RecordingId get() = data.recording.id

    companion object {
        /** D9 step 4: `store.unfinished()`, then everything the prompt and a Continue need, read once. */
        suspend fun find(deps: AppDeps): UnfinishedRun? {
            val recording = deps.store.unfinished() ?: return null
            val data = deps.store.load(recording.id) ?: return null
            // D24: the profile stored with the recording's metrics, else the current one. The port hands back no
            // metrics (`RecordingData` has none), so until a D# adds them the current profile is the one reachable branch.
            val config = RunConfig(recording.id, recording.type, deps.profile, recording.tzOffset)
            val rebuilt = RunReducer.rebuild(config, data)
            val savedUntil = data.lastPersistedAt ?: recording.startUtc
            val snapshot = rebuilt.snapshot
            val facts = CrashFacts(
                state = recording.state, startedAt = recording.startUtc, tzOffset = recording.tzOffset,
                distance = snapshot.distance, totalTime = snapshot.totalTime,
                savedUntil = savedUntil,
            )
            return UnfinishedRun(data, rebuilt, facts)
        }
    }
}
