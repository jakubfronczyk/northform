package com.jakubfronczyk.northform.core.ports

import com.jakubfronczyk.northform.core.MetricResult
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.metrics.Headline

/**
 * A saved recording as Today and History list it: type, tag and start from [recording], the numbers from [headline].
 * [profile] is the one its metrics were stored with (D24), for reopening it as it was computed; null with no metric rows.
 */
data class SavedRecording(val recording: Recording, val headline: Headline, val profile: Profile? = null)

/**
 * The read side of saved recordings (today-screen, history-screen specs): the app reads every one into one list that
 * Today and History share. Read-only; the
 * frozen [RecordingStore] is untouched. Computing, failed or crash-left recordings never appear.
 */
interface SavedRecordings {
    /** At most [n] saved recordings, newest start first. */
    suspend fun latest(n: Int): List<SavedRecording>

    /** Nothing saved: for wiring that never reads Today (like `BackgroundTasks.None`). */
    object None : SavedRecordings {
        override suspend fun latest(n: Int): List<SavedRecording> = emptyList()
    }
}

/** One saved recording as a store holds it: the row and the metric rows Save wrote (possibly without a [Headline]'s). */
data class SavedRow(val recording: Recording, val metrics: List<MetricResult>)

/**
 * What a store adds for [SavedRecordings], beside the frozen port like [ReliabilityStore]: the saved rows, newest
 * first. Turning them into headlines (and computing the ones saved before Save stored them) is the engine's.
 */
interface SavedRows {
    suspend fun savedNewestFirst(limit: Int): List<SavedRow>
}
