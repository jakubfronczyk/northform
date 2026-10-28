package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.SavedRecording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The History artboard (`Home.dc.html`) as a table over [HistoryModel.from] (history-screen/01). */
class HistoryModelTest {
    /** Wed 1 Oct 2025, 09:00 in UTC+2. */
    private val now = Instant.parse("2025-10-01T07:00:00Z")
    private val tz = 7200

    private fun saved(id: String, type: RecordingType, tag: RunTag?, start: String, km: Double, total: Int, hr: Int?, zone: Int = tz) = SavedRecording(
        Recording(id = RecordingId(id), type = type, tag = tag, state = RecordingState.Saved, startUtc = Instant.parse(start), tzOffset = zone),
        Headline(km * 1000, total.seconds, total.seconds, hr),
    )

    private val tempo = saved("tempo", RecordingType.Run, RunTag.Tempo, "2025-09-30T05:12:00Z", 10.21, (52.minutes + 40.seconds).inWholeSeconds.toInt(), 156)
    private val walk = saved("walk", RecordingType.Walk, null, "2025-09-27T15:45:00Z", 4.10, (48.minutes + 12.seconds).inWholeSeconds.toInt(), null)

    private fun listed(vararg saved: SavedRecording) = assertIs<HistoryModel.Listed>(HistoryModel.from(SavedList.Loaded(saved.toList()), now, tz))

    @Test
    fun every_saved_recording_is_a_row_newest_first_formatted_as_today_s_recent() {
        val m = listed(tempo, walk)
        assertEquals(listOf("Run · Tempo", "Walk"), m.rows.map { it.title })
        assertEquals(listOf("Tue 30 Sep · 07:12", "Sat 27 Sep · 17:45"), m.rows.map { it.date })
        assertEquals(listOf("10.21", "4.10"), m.rows.map { it.distance })
        assertEquals(listOf("52:40 · 156 bpm", "48:12"), m.rows.map { it.detail })
        assertEquals(listOf(RecordingType.Run, RecordingType.Walk), m.rows.map { it.type })
    }

    @Test
    fun the_count_line_is_singular_and_plural() {
        assertEquals("1 recording", listed(tempo).count)
        assertEquals("2 recordings", listed(tempo, walk).count)
    }

    @Test
    fun nothing_saved_is_nothing_here_yet() {
        val m = listed()
        assertEquals("Nothing here yet", m.emptyLine)
        assertEquals(emptyList(), m.rows)
    }

    @Test
    fun loading_shows_nothing_and_a_failed_read_offers_retry() {
        assertEquals(HistoryModel.Loading, HistoryModel.from(SavedList.Loading, now, tz))
        assertEquals(HistoryModel.Failed, HistoryModel.from(SavedList.Failed(listOf(tempo), "disk"), now, tz))
        assertEquals("Couldn't load history", HistoryModel.Failed.line)
    }

    /** E17, GLOSSARY "Day": 23:40 → 00:20 belongs to the day it started, in its own zone. */
    @Test
    fun a_recording_over_midnight_is_on_the_day_it_started() {
        val late = saved("late", RecordingType.Run, null, "2025-09-29T21:40:00Z", 5.0, 40 * 60, 140) // Mon 23:40 in UTC+2
        assertEquals("Mon 29 Sep · 23:40", listed(late).rows.single().date)
    }

    @Test
    fun a_recording_from_an_earlier_year_shows_its_year_and_this_year_s_do_not() {
        val lastYear = saved("old", RecordingType.Run, null, "2024-09-30T05:12:00Z", 5.0, 30 * 60, 140)
        assertEquals(listOf("Tue 30 Sep · 07:12", "Mon 30 Sep 2024 · 07:12"), listed(tempo, lastYear).rows.map { it.date })
    }

    /** The year is the recording's own: 01:30 UTC on 1 Jan 2025 is still New Year's Eve 2024 in UTC−3. */
    @Test
    fun the_year_is_read_in_the_recording_s_own_zone() {
        val nye = saved("nye", RecordingType.Run, null, "2025-01-01T01:30:00Z", 5.0, 30 * 60, 140, zone = -3 * 3600)
        assertEquals("Tue 31 Dec 2024 · 22:30", listed(nye).rows.single().date)
    }
}
