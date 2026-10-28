package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.core.ports.SavedRecordings
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.engine.run.compute
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SavedRecordings] over any store (today-screen spec): the headline is read from the stored metric rows; a recording
 * saved before Save stored them is computed from its streams on read and not written back (no backfill).
 *
 * history-screen/01: one recording whose numbers cannot be read or computed is left out and [log]ged, so a damaged
 * recording never takes the whole list (History reads everything) down with it.
 */
class StoredSavedRecordings(
    private val rows: SavedRows,
    private val store: RecordingStore,
    private val log: (String) -> Unit = {},
) : SavedRecordings {
    override suspend fun latest(n: Int): List<SavedRecording> = rows.savedNewestFirst(n).mapNotNull { row ->
        runCatching {
            val headline = Headline.of(row.metrics)
                ?: store.load(row.recording.id)?.let(Headline::compute)
                ?: return@mapNotNull null // deleted between the two reads
            SavedRecording(row.recording, headline, profile = row.metrics.firstOrNull()?.profile) // Save stores every row with one profile
        }.onFailure { e ->
            if (e is CancellationException) throw e // the read was cancelled, not the recording damaged
            log("saved ${row.recording.id.value} left out: $e")
        }.getOrNull()
    }
}
