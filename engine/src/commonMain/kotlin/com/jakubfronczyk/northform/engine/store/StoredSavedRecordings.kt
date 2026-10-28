package com.jakubfronczyk.northform.engine.store

import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.RecordingStore
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.core.ports.SavedRecordings
import com.jakubfronczyk.northform.core.ports.SavedRows
import com.jakubfronczyk.northform.engine.run.compute

/**
 * [SavedRecordings] over any store (today-screen spec): the headline is read from the stored metric rows; a recording
 * saved before Save stored them is computed from its streams on read and not written back (no backfill).
 */
class StoredSavedRecordings(private val rows: SavedRows, private val store: RecordingStore) : SavedRecordings {
    override suspend fun latest(n: Int): List<SavedRecording> = rows.savedNewestFirst(n).mapNotNull { row ->
        val headline = Headline.of(row.metrics)
            ?: store.load(row.recording.id)?.let(Headline::compute)
            ?: return@mapNotNull null // deleted between the two reads
        SavedRecording(row.recording, headline)
    }
}
