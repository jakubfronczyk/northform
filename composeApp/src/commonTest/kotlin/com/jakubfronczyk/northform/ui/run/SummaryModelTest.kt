package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.core.EventKind
import com.jakubfronczyk.northform.core.EventSource
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingEvent
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.RecordingData
import com.jakubfronczyk.northform.engine.run.RunSummary
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.at
import com.jakubfronczyk.northform.testing.fix
import kotlin.test.Test
import kotlin.test.assertEquals

/** The SummaryDark artboard's top block as a table over [SummaryModel.from] (summary-screen/01). */
class SummaryModelTest {
    private val config = RunHarness.config

    /** 5 s → 65 s, paused 30–40 s, 3 m/s while moving, HR 150; one sensor drop 20–26 s; cool-down to 125 s. */
    private fun stored(type: RecordingType = RecordingType.Run) = RecordingData(
        recording = Recording(id = config.recordingId, type = type, state = RecordingState.Computing, startUtc = at(5), tzOffset = config.tzOffset, endUtc = at(65)),
        hr = (6..65).filter { it !in 20..25 }.map { HrSample(at(it), 150) } + (66..125).map { HrSample(at(it), 120) },
        fixes = (6..65).map { s -> fix(s, north = 3.0 * (minOf(s, 30) - 5) + 3.0 * maxOf(0, s - 40)) },
        events = listOf(
            RecordingEvent(at(20), EventKind.SensorLost, EventSource.Auto),
            RecordingEvent(at(25), EventKind.HrLost, EventSource.Auto),
            RecordingEvent(at(26), EventKind.SensorReconnected, EventSource.Auto),
            RecordingEvent(at(26), EventKind.HrBack, EventSource.Auto),
            RecordingEvent(at(30), EventKind.Pause, EventSource.Manual),
            RecordingEvent(at(40), EventKind.Resume, EventSource.Manual),
            RecordingEvent(at(65), EventKind.Stop, EventSource.Manual),
            RecordingEvent(at(125), EventKind.CooldownEnd, EventSource.Auto),
        ),
        lastPersistedAt = at(125),
    )

    private fun model(type: RecordingType = RecordingType.Run) = SummaryModel.from(RunSummary.compute(stored(type), config.profile))

    @Test
    fun the_top_block_shows_the_run_the_way_the_artboard_does() {
        val m = model()
        assertEquals("Run", m.typeLabel)
        assertEquals("Mon 12 Jan · 15:46", m.dateLine, "the recording's own zone (UTC+2), epoch 1 000 005 s")
        assertEquals("0.14", m.distance, "144 m: 24 segments before the pause, 24 after (the first fix after Resume re-anchors)")
        assertEquals("1:00", m.totalTime, "stop − start, the pause included")
        assertEquals("moving 0:50", m.movingLine)
        assertEquals("5:47", m.avgPace, "50 s active over 144 m")
        assertEquals("150", m.avgHr)
        assertEquals("Data · 1 sensor drop · 1 s gap · 0 crashes", m.dataQuality)
    }

    @Test
    fun a_walk_says_walk_and_a_missing_heart_rate_is_a_dash() {
        val data = stored(RecordingType.Walk).let { it.copy(hr = emptyList(), events = it.events.filter { e -> e.kind != EventKind.HrLost && e.kind != EventKind.HrBack }) }
        val m = SummaryModel.from(RunSummary.compute(data, config.profile))
        assertEquals("Walk", m.typeLabel)
        assertEquals(NO_VALUE, m.avgHr)
    }
}

/** The preview fixture goes through the real engine: it must compute, and show the artboard's headline numbers. */
class FakeSummaryTest {
    @Test
    fun the_run_fixture_shows_the_artboard_numbers() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals("Run", m.typeLabel)
        assertEquals("Tue 30 Sep · 07:12", m.dateLine)
        assertEquals("52:40", m.totalTime)
        assertEquals("moving 52:30", m.movingLine)
        assertEquals("10.19", m.distance)
        assertEquals("Data · 1 sensor drop · 6 s gap · 0 crashes", m.dataQuality)
    }

    @Test
    fun the_walk_fixture_has_no_heart_rate() {
        val m = SummaryModel.from(FakeSummary.walk)
        assertEquals("Walk", m.typeLabel)
        assertEquals(NO_VALUE, m.avgHr)
    }
}

/** summary-screen/02: the "Your run" block. */
class SummaryEditsModelTest {
    @Test
    fun a_run_offers_the_tag_chips_with_the_suggestion_preselected() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals("Your run", m.yourLabel)
        assertEquals(true, m.showsTags)
        assertEquals(FakeSummary.run.suggestedTag, m.suggestedTag)
    }

    @Test
    fun a_walk_has_no_tag_chips() {
        val m = SummaryModel.from(FakeSummary.walk)
        assertEquals("Your walk", m.yourLabel)
        assertEquals(false, m.showsTags)
    }
}

/** summary-screen/03: the "Heart rate" card and the splits table over the preview fixture. */
class SummaryHeartRateModelTest {
    @Test
    fun the_heart_rate_card_shows_average_max_and_the_recovery_with_its_readings() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals("149", m.hrAverage)
        assertEquals("171", m.hrMax)
        assertEquals("−31", m.recovery)
        assertEquals("171 → 140", m.recoveryDetail)
    }

    @Test
    fun the_line_breaks_at_the_sensor_gap_and_labels_it_from_ten_seconds() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals(listOf("sensor gap 12 s"), m.gapLabels.map { it.text })
        assertEquals(6, m.bands.size, "one band per zone, Z0 to Z5")
        assertEquals(true, m.linePoints.isNotEmpty())
    }

    @Test
    fun zone_rows_add_up_to_the_effort_and_splits_list_every_km_with_the_partial_last_one() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals(listOf("Z0", "Z1", "Z2", "Z3", "Z4", "Z5"), m.zones.map { it.label })
        assertEquals(11, m.splits.size, "10 full km and the partial 11th")
        assertEquals("1", m.splits.first().km)
        assertEquals(true, m.splits.last().partial)
        assertEquals("5:10", m.splits.first().pace)
    }

    @Test
    fun a_walk_without_a_sensor_shows_dashes_and_no_line() {
        val m = SummaryModel.from(FakeSummary.walk)
        assertEquals(NO_VALUE, m.hrAverage)
        assertEquals(NO_VALUE, m.recovery)
        assertEquals("skipped", m.recoveryDetail)
        assertEquals(emptyList(), m.linePoints)
    }
}

class FakeSummarySkippedTest {
    @Test
    fun the_skipped_cool_down_fixture_has_no_recovery() {
        val m = SummaryModel.from(FakeSummary.skippedCooldown)
        assertEquals(NO_VALUE, m.recovery)
        assertEquals("skipped", m.recoveryDetail)
    }
}

/** summary-screen/04: the HRmax card and the map's route. */
class SummaryHrMaxModelTest {
    @Test
    fun a_higher_hrmax_held_long_enough_offers_the_card() {
        val m = SummaryModel.from(FakeSummary.newHrMax)
        assertEquals("191", m.hrMaxSuggestion)
        assertEquals("187", m.hrMaxCurrent)
    }

    @Test
    fun no_suggestion_hides_the_card_and_the_route_has_a_break_at_the_pause() {
        val m = SummaryModel.from(FakeSummary.run)
        assertEquals(null, m.hrMaxSuggestion)
        assertEquals(2, m.route.segments.size)
    }
}
