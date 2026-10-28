package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.ui.run.SummaryModel
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type

/**
 * The History artboard (`Home.dc.html`, history-screen spec): Back, the title, the count and every saved recording
 * newest first, in the rows Today's Recent uses. Lays out a [HistoryModel]; decides nothing. [listState] comes from
 * the root so the scroll position survives a trip into a reopened Summary.
 */
@Composable
fun HistoryScreen(
    model: HistoryModel,
    /** A pill tapped: the new filter (the root holds it). */
    onFilter: (HistoryFilter) -> Unit,
    back: () -> Unit,
    retry: () -> Unit,
    openRecording: (RecordingId) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.offset(x = (-10).dp).size(44.dp).clickable(onClick = back), contentAlignment = Alignment.Center) {
                NfIcon(NfIcons.back, Theme.text, size = 22.dp, label = "Back to Today")
            }
            Text("History", style = Type.number(28, FontWeight.ExtraBold), color = Theme.text)
            if (model is HistoryModel.Listed) Filters(model, onFilter)
        }
        when (model) {
            HistoryModel.Loading -> Unit
            HistoryModel.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(HistoryModel.Failed.line, style = Type.statusLabel.copy(fontSize = 16.sp), color = Theme.text)
                Text("Retry", style = Type.statusLabel.copy(fontSize = 15.sp), color = Theme.accent, modifier = Modifier.clickable(onClick = retry).padding(vertical = 8.dp))
            }
            is HistoryModel.Listed -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 50.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        // Different sizes on one baseline, as the artboard's `align-items: baseline`.
                        Text(model.count, style = Type.tertiaryText, color = Theme.text2, modifier = Modifier.alignByBaseline())
                        Text("Newest first", style = Type.tertiaryText.copy(fontSize = 12.sp), color = Theme.text3, modifier = Modifier.alignByBaseline())
                    }
                }
                model.empty?.let { empty ->
                    // Artboard: centred under a dashed rule; "Nothing here yet" as a title, the filters' line in grey.
                    val nothingSaved = empty == HistoryModel.Empty.NothingSaved
                    item {
                        DashedLine()
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text(empty.line, style = if (nothingSaved) Type.statusLabel.copy(fontSize = 16.sp) else Type.tertiaryText, color = if (nothingSaved) Theme.text else Theme.text2)
                        }
                    }
                }
                items(model.rows, key = { it.id.value }) { row ->
                    DashedLine()
                    RecentRow(row) { openRecording(row.id) }
                }
            }
        }
    }
}

/** All / Run / Walk, then the run tags (not for walks), as on the artboard. */
@Composable
private fun Filters(model: HistoryModel.Listed, onFilter: (HistoryFilter) -> Unit) {
    val f = model.filter
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (type in listOf(null, RecordingType.Run, RecordingType.Walk)) {
                FilterPill(type?.let(SummaryModel::title) ?: "All", selected = f.type == type, horizontal = 20.dp, fontSize = 15) { onFilter(f.pick(type)) }
            }
        }
        if (model.showsTags) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in RunTag.entries) FilterPill(tag.name, selected = f.tag == tag, horizontal = 14.dp, fontSize = 14) { onFilter(f.toggle(tag)) }
            }
        }
    }
}

private val PillShape = RoundedCornerShape(22.dp)

@Composable
private fun FilterPill(text: String, selected: Boolean, horizontal: Dp, fontSize: Int, onClick: () -> Unit) {
    val framed = if (selected) Modifier.background(Theme.text, PillShape) else Modifier.border(1.5.dp, Theme.outline, PillShape)
    Box(framed.height(44.dp).clip(PillShape).clickable(onClick = onClick).padding(horizontal = horizontal), contentAlignment = Alignment.Center) {
        Text(text, style = Type.statusLabel.copy(fontSize = fontSize.sp), color = if (selected) Theme.bg else Theme.text)
    }
}
