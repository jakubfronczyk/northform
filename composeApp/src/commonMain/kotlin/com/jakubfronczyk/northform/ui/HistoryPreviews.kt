package com.jakubfronczyk.northform.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag

// History (history-screen/01, 02) on Today's fake week.
@Composable
private fun previewHistory(saved: SavedList, filter: HistoryFilter = HistoryFilter()) =
    HistoryScreen(HistoryModel.from(saved, filter, previewNow, PREVIEW_TZ), onFilter = {}, back = {}, retry = {})

@Preview @Composable fun HistoryPreview() = previewHistory(SavedList.Loaded(previewWeek))
@Preview @Composable fun HistoryNothingSavedPreview() = previewHistory(SavedList.Loaded(emptyList()))
@Preview @Composable fun HistoryFailedPreview() = previewHistory(SavedList.Failed(previewWeek))
@Preview @Composable fun HistoryTempoPreview() = previewHistory(SavedList.Loaded(previewWeek), HistoryFilter(tag = RunTag.Tempo))
@Preview @Composable fun HistoryNoMatchPreview() = previewHistory(SavedList.Loaded(previewWeek), HistoryFilter(type = RecordingType.Run, tag = RunTag.Intervals))
