package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.engine.run.SummaryEdits
import com.jakubfronczyk.northform.engine.run.SummarySession
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import com.jakubfronczyk.northform.ui.ValueCell

/**
 * The Summary (R27, `SummaryDark.dc.html`): draws one [SummarySession.State] and decides nothing (previews pass a state built on [FakeSummary]). Computing
 * shows a title only; Ready shows the top block ([SummaryModel]) and Save. The sections below the top
 * block (heart rate, splits, the edits, the map) come with summary-screen/02–04.
 */
@Composable
fun SummaryScreen(state: SummarySession.State, onSave: (SummaryEdits) -> Unit) {
    when (val s = state) {
        SummarySession.State.Computing -> Box(Modifier.fillMaxSize().background(Theme.bg), contentAlignment = Alignment.Center) {
            Text("Computing…", style = Type.secondaryText, color = Theme.text2)
        }
        is SummarySession.State.Ready -> SummaryContent(SummaryModel.from(s.summary), error = s.error, onSave = { onSave(SummaryEdits(tag = s.summary.suggestedTag, rpe = null, notes = null)) })
        SummarySession.State.Saved -> Unit // the root removes the screen
    }
}

@Composable
private fun SummaryContent(m: SummaryModel, error: String?, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Theme.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(m.typeLabel, style = Type.pillLabel, color = Theme.text, modifier = Modifier.background(Theme.card, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text(m.dateLine, style = Type.secondaryText, color = Theme.text2)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            ValueCell(m.distance, Modifier.weight(1f), labelText = "Distance", unit = "km", valueStyle = Type.number40)
            ValueCell(m.totalTime, Modifier.weight(1f), labelText = "Total time", valueStyle = Type.number40, footer = { Text(m.movingLine, style = Type.tertiaryText, color = Theme.text3) })
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            ValueCell(m.avgPace, Modifier.weight(1f), labelText = "Avg pace", unit = "/km", valueStyle = Type.number40)
            ValueCell(m.avgHr, Modifier.weight(1f), labelText = "Avg heart rate", unit = "bpm", valueStyle = Type.number40)
        }
        Spacer(Modifier.height(20.dp))
        Text(m.dataQuality, style = Type.tertiaryText, color = Theme.text3)
        Spacer(Modifier.height(32.dp))
        if (error != null) {
            Text("Couldn't save: $error", style = Type.secondaryText, color = Theme.warn)
            Spacer(Modifier.height(12.dp))
        }
        BigButton("Save ${m.typeLabel.lowercase()}", onSave)
    }
}
