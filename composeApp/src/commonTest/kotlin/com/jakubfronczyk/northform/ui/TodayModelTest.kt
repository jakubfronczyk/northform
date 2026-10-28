package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.ui.run.GateSensor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Today artboard as a table over [TodayModel.from] (today-screen/01–03). */
class TodayModelTest {
    /** Wed 1 Oct 2025, 09:00 in UTC+2. */
    private val now = Instant.parse("2025-10-01T07:00:00Z")
    private val tz = 7200

    private fun saved(id: String, type: RecordingType, tag: RunTag?, start: String, headline: Headline) = SavedRecording(
        Recording(id = RecordingId(id), type = type, tag = tag, state = RecordingState.Saved, startUtc = Instant.parse(start), tzOffset = tz),
        headline,
    )

    /** Tue 30 Sep 07:12 local: 10.21 km in 52:40, 5:09 /km over the moving time, 156 bpm. */
    private val tempo = saved("tempo", RecordingType.Run, RunTag.Tempo, "2025-09-30T05:12:00Z", Headline(10_210.0, 52.minutes + 40.seconds, (52.5 * 60).seconds, 156))
    private val long = saved("long", RecordingType.Run, RunTag.Long, "2025-09-28T06:30:00Z", Headline(16_800.0, 92.minutes + 5.seconds, 90.minutes, 148))
    private val walk = saved("walk", RecordingType.Walk, null, "2025-09-27T15:45:00Z", Headline(4_100.0, 48.minutes + 12.seconds, 48.minutes, 98))
    private val walkNoHr = saved("walk", RecordingType.Walk, null, "2025-09-27T15:45:00Z", Headline(4_100.0, 48.minutes + 12.seconds, 48.minutes, null))

    private val connected = GateSensor(connected = true, batteryPercent = 82, paired = true)

    private fun model(vararg saved: SavedRecording, sensor: GateSensor? = connected, name: String? = "Verity Sense") =
        TodayModel.from(saved.toList(), sensor, name, now, tz)

    @Test
    fun the_date_is_today_in_the_local_zone() = assertEquals("Wed 1 Oct", model().date)

    @Test
    fun nothing_saved_is_the_empty_line_with_no_card_and_no_recent() {
        val m = model()
        assertNull(m.last)
        assertEquals(emptyList(), m.recent)
        assertEquals("No recordings yet", m.emptyLine)
    }

    @Test
    fun the_newest_run_is_the_card_with_its_tag_and_the_summary_formatting() {
        val card = model(tempo).last!!
        assertEquals("Last run · Tue 30 Sep · Tempo", card.label)
        assertEquals("10.21", card.distance)
        assertEquals("52:40 · 5:09 /km · 156 bpm", card.detail)
        assertEquals(RecordingId("tempo"), card.id)
        assertNull(model(tempo).emptyLine)
    }

    @Test
    fun a_walk_card_has_no_tag_and_no_heart_rate_leaves_the_bpm_out() {
        assertEquals("Last walk · Sat 27 Sep", model(walk).last!!.label)
        assertEquals("48:12 · 11:42 /km", model(walkNoHr).last!!.detail)
    }

    @Test
    fun a_pace_under_the_minimum_distance_shows_the_en_dash() {
        val short = saved("short", RecordingType.Run, null, "2025-09-30T05:12:00Z", Headline(20.0, 30.seconds, 30.seconds, 120))
        assertEquals("0:30 · –:–– /km · 120 bpm", model(short).last!!.detail)
    }

    @Test
    fun one_recording_has_no_recent_and_the_next_two_never_repeat_the_card() {
        assertEquals(emptyList(), model(tempo).recent)
        val m = model(tempo, long, walk)
        assertEquals(listOf("Run · Long", "Walk"), m.recent.map { it.title })
        assertEquals(listOf("Sun 28 Sep · 08:30", "Sat 27 Sep · 17:45"), m.recent.map { it.date })
        assertEquals(listOf("16.80", "4.10"), m.recent.map { it.distance })
        assertEquals(listOf("1:32:05 · 148 bpm", "48:12 · 98 bpm"), m.recent.map { it.detail })
        assertEquals(listOf(RecordingType.Run, RecordingType.Walk), m.recent.map { it.type })
    }

    @Test
    fun a_recent_walk_without_heart_rate_shows_only_its_time() =
        assertEquals("48:12", model(tempo, walkNoHr).recent.single().detail)

    @Test
    fun the_sensor_card_reads_the_link() {
        assertEquals(TodayModel.Sensor("Verity Sense", connected = true, "Connected · battery 82 %"), model().sensor)
        assertEquals("Connected", model(sensor = connected.copy(batteryPercent = null)).sensor.status)
        assertEquals(TodayModel.Sensor("Verity Sense", connected = false, "Not connected · tap to connect"), model(sensor = connected.copy(connected = false)).sensor)
    }

    @Test
    fun an_unknown_link_or_name_shows_a_sensor_that_is_not_connected() {
        assertEquals(TodayModel.Sensor("Heart rate sensor", connected = false, "Not connected · tap to connect"), model(sensor = null, name = null).sensor)
    }
}
