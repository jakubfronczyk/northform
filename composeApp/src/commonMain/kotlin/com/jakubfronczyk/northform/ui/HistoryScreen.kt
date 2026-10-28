package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RecordingId

/**
 * The History artboard (`Home.dc.html`, history-screen spec): Back, the title, the count and every saved recording
 * newest first, in the rows Today's Recent uses. Lays out a [HistoryModel]; decides nothing. [listState] comes from
 * the root so the scroll position survives a trip into a reopened Summary.
 */
@Composable
fun HistoryScreen(
    model: HistoryModel,
    back: () -> Unit,
    retry: () -> Unit,
    openRecording: (RecordingId) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    Column(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 54.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.offset(x = (-10).dp).size(44.dp).clickable(onClick = back), contentAlignment = Alignment.Center) {
                Text("‹", style = Type.number(30), color = Theme.text)
            }
            Text("History", style = Type.number(28, FontWeight.ExtraBold), color = Theme.text)
        }
        when (model) {
            HistoryModel.Loading -> Unit
            HistoryModel.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(HistoryModel.Failed.line, style = Type.statusLabel.copy(fontSize = 16.sp), color = Theme.text)
                Text("Retry", style = Type.statusLabel.copy(fontSize = 15.sp), color = Theme.accent, modifier = Modifier.clickable(onClick = retry).padding(vertical = 8.dp))
            }
            is HistoryModel.Listed -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 50.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(model.count, style = Type.tertiaryText, color = Theme.text2)
                        Text("Newest first", style = Type.tertiaryText.copy(fontSize = 12.sp), color = Theme.text3)
                    }
                }
                model.emptyLine?.let { line ->
                    item { Text(line, style = Type.statusLabel.copy(fontSize = 16.sp), color = Theme.text, modifier = Modifier.padding(top = 24.dp)) }
                }
                items(model.rows, key = { it.id.value }) { row ->
                    DashedLine()
                    RecentRow(row) { openRecording(row.id) }
                }
            }
        }
    }
}
