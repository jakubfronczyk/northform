package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.reliability.ReliabilityRecord

/**
 * Where a recording's reliability log (R29) is cached. Separate from [RecordingStore] on purpose: that
 * port is a frozen contract, and the log is a pure function of the recording's data, so a store that
 * cannot keep it loses nothing but a recompute.
 */
interface ReliabilityStore {
    /** Upsert, any state; `NotFound` if the recording does not exist. */
    suspend fun saveReliability(id: RecordingId, record: ReliabilityRecord)
}
