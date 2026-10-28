package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jakubfronczyk.northform.core.Recording
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.core.metrics.Headline
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.ui.run.GateSensor
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The Today artboard's states on a fake list (today-screen spec): the 16e check is the visual test.

internal val previewNow = Instant.parse("2025-10-01T07:00:00Z")
internal const val PREVIEW_TZ = 7200
private val connected = GateSensor(connected = true, batteryPercent = 82, paired = true)

private fun saved(id: String, type: RecordingType, tag: RunTag?, start: String, km: Double, total: Int, hr: Int?) = SavedRecording(
    Recording(id = RecordingId(id), type = type, tag = tag, state = RecordingState.Saved, startUtc = Instant.parse(start), tzOffset = PREVIEW_TZ),
    Headline(km * 1000, total.seconds, (total - 30).seconds, hr),
)

internal val previewWeek = listOf(
    saved("tempo", RecordingType.Run, RunTag.Tempo, "2025-09-30T05:12:00Z", 10.21, (52.minutes + 40.seconds).inWholeSeconds.toInt(), 156),
    saved("long", RecordingType.Run, RunTag.Long, "2025-09-28T06:30:00Z", 16.80, (92.minutes + 5.seconds).inWholeSeconds.toInt(), 148),
    saved("walk", RecordingType.Walk, null, "2025-09-27T15:45:00Z", 4.10, (48.minutes + 12.seconds).inWholeSeconds.toInt(), 98),
)

@Composable
private fun preview(saved: List<SavedRecording>, sensor: GateSensor? = connected) = Box(Modifier.fillMaxSize().background(Theme.bg)) {
    TodayScreen(TodayModel.from(saved, sensor, "Verity Sense", previewNow, PREVIEW_TZ), start = {}, openSensor = {}, openRecording = {}, openHistory = {}, openSettings = {})
}

@Preview @Composable fun TodayPreview() = preview(previewWeek)
@Preview @Composable fun TodayOneRunPreview() = preview(previewWeek.take(1))
@Preview @Composable fun TodayNoRecordingsPreview() = preview(emptyList())
@Preview @Composable fun TodaySensorOffPreview() = preview(previewWeek, connected.copy(connected = false, batteryPercent = null))

