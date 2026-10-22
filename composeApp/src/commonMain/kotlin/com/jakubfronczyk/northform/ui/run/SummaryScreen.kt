package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RunTag
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.SummaryEdits
import com.jakubfronczyk.northform.engine.run.SummarySession
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import com.jakubfronczyk.northform.ui.ValueCell

/**
 * The Summary (R27, `SummaryDark.dc.html`, `SummaryWalk` = the same board for a walk): draws one
 * [SummarySession.State] and decides nothing (previews pass a state built on [FakeSummary]). Computing
 * shows a title only; Ready shows the top block ([SummaryModel]), the "Your run" edits and Save, with the
 * bin that opens the confirm sheet (`DeleteConfirm.dc.html`); the heart-rate and splits cards are
 * [SummaryCards.kt]; the route map sits on top (summary-screen/04).
 */
@Composable
fun SummaryScreen(
    state: SummarySession.State,
    onSave: (SummaryEdits) -> Unit,
    onDelete: () -> Unit,
    /** SU6: the HRmax card's Update. */
    onAcceptHrMax: () -> Unit = {},
    /** The platform map (D13), as the run flow injects it; it reads [LocalMapCamera], provided here fitted to the route. */
    map: @Composable (route: Route) -> Unit = { route -> RouteCanvas(route) },
) {
    when (val s = state) {
        SummarySession.State.Computing -> Box(Modifier.fillMaxSize().background(Theme.bg), contentAlignment = Alignment.Center) {
            Text("Computing…", style = Type.secondaryText, color = Theme.text2)
        }
        is SummarySession.State.Ready -> SummaryContent(SummaryModel.from(s.summary), error = s.error, busy = s.busy, onSave = onSave, onDelete = onDelete, onAcceptHrMax = onAcceptHrMax, map = map)
        SummarySession.State.Saved, SummarySession.State.Deleted -> Unit // the root removes the screen
    }
}

@Composable
private fun SummaryContent(m: SummaryModel, error: String?, busy: Boolean, onSave: (SummaryEdits) -> Unit, onDelete: () -> Unit, onAcceptHrMax: () -> Unit, map: @Composable (route: Route) -> Unit) {
    // The edits live here, with the screen (R27): they go to the store only on Save.
    var tag by remember(m.suggestedTag) { mutableStateOf(m.suggestedTag) }
    var rpe by remember { mutableStateOf<Int?>(null) }
    var notes by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    // One fitted camera for this Summary: the whole route once, then the user's pans and zooms (summary-screen/04).
    val camera = remember { MapCamera(fitted = true) }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 50.dp)) {
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                CompositionLocalProvider(LocalMapCamera provides camera) { map(m.route) }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(m.typeLabel, style = Type.pillLabel, color = Theme.text, modifier = Modifier.background(Theme.card, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                Text(m.dateLine, style = Type.secondaryText, color = Theme.text2)
                Spacer(Modifier.weight(1f))
                BinButton(enabled = !busy) { confirmDelete = true }
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
            Spacer(Modifier.height(24.dp))

            if (m.hrMaxSuggestion != null) {
                HrMaxCard(m.hrMaxSuggestion, m.hrMaxCurrent, enabled = !busy, onAccept = onAcceptHrMax)
                Spacer(Modifier.height(16.dp))
            }
            HeartRateCard(m)
            Spacer(Modifier.height(16.dp))
            SplitsCard(m)
            Spacer(Modifier.height(16.dp))
            EditsCard(m, tag, { tag = it }, rpe, { rpe = it }, notes, { notes = it })
            Spacer(Modifier.height(24.dp))

            if (error != null) {
                Text("Couldn't save: $error", style = Type.secondaryText, color = Theme.warn)
                Spacer(Modifier.height(12.dp))
            }
            BigButton("Save ${m.typeLabel.lowercase()}", enabled = !busy, onClick = { onSave(SummaryEdits(tag = tag, rpe = rpe, notes = notes.trim().ifEmpty { null })) })
            }
        }
        if (confirmDelete) DeleteConfirmSheet(m, onKeep = { confirmDelete = false }, onDelete = { confirmDelete = false; onDelete() })
    }
}

/** SU6: "New max heart rate: 191 — Higher than your 187. Update zones?" with Update; recomputes this Summary only. */
@Composable
private fun HrMaxCard(suggested: String, current: String, enabled: Boolean, onAccept: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(18.dp)).border(1.dp, Theme.accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("New max heart rate: $suggested", style = Type.sheetTitle, color = Theme.text)
            Text("Higher than your $current. Update zones?", style = Type.secondaryText, color = Theme.text2)
        }
        Spacer(Modifier.width(12.dp))
        BigButton("Update", onAccept, modifier = Modifier.width(96.dp), enabled = enabled, textStyle = Type.statusLabel)
    }
}

/** The "Your run" card: Type chips (a run only), "How hard did it feel?" 0–10 in two rows, Notes. */
@Composable
private fun EditsCard(m: SummaryModel, tag: RunTag?, onTag: (RunTag?) -> Unit, rpe: Int?, onRpe: (Int?) -> Unit, notes: String, onNotes: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(m.yourLabel, style = Type.sheetTitle, color = Theme.text)
        if (m.showsTags) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type", style = Type.tertiaryText, color = Theme.text2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in RunTag.entries) OptionPill(t.name, selected = t == tag, modifier = Modifier.weight(1f)) { onTag(if (t == tag) null else t) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("How hard did it feel?", style = Type.tertiaryText, color = Theme.text2)
                Spacer(Modifier.weight(1f))
                Text(if (rpe == null) "—" else "$rpe / 10", style = Type.tertiaryText, color = Theme.text)
            }
            for (row in listOf(0..5, 6..10)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in row) OptionPill("$n", selected = n == rpe, modifier = Modifier.weight(1f)) { onRpe(if (n == rpe) null else n) }
                    if (row.first == 6) Spacer(Modifier.weight(1f))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notes", style = Type.tertiaryText, color = Theme.text2)
            Box(Modifier.fillMaxWidth().background(Theme.bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (notes.isEmpty()) Text("Legs heavy after km 7", style = Type.secondaryText, color = Theme.text3)
                BasicTextField(notes, onNotes, textStyle = Type.secondaryText.copy(color = Theme.text), cursorBrush = SolidColor(Theme.accent), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One selectable pill of the Type row and the RPE grid: filled in the accent when selected, outlined otherwise. */
@Composable
private fun OptionPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val framed = if (selected) modifier.background(Theme.accent, shape) else modifier.border(1.dp, Theme.line, shape)
    Box(framed.height(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = Type.statusLabel, color = if (selected) Theme.bg else Theme.text)
    }
}

/** The bin in the top row: opens the confirm sheet; disabled while a store call is in flight. */
@Composable
private fun BinButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).border(1.dp, Theme.line, RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("🗑", style = TextStyle(fontSize = 16.sp), color = if (enabled) Theme.text2 else Theme.dim) }
}

/** `DeleteConfirm.dc.html` (R27: the second of the two taps): a scrim and a bottom sheet with the run's headline numbers. */
@Composable
private fun DeleteConfirmSheet(m: SummaryModel, onKeep: () -> Unit, onDelete: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onKeep)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Theme.card, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 50.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Delete this ${m.typeLabel.lowercase()}?", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black), color = Theme.text)
            Text("It goes for good: ${m.distance} km · ${m.totalTime} · ${m.avgHr} bpm.", style = Type.secondaryText, color = Theme.text2)
            BigButton("Delete for good", onDelete, fill = Color(0xFFB83A2E))
            BigButton("Keep ${m.typeLabel.lowercase()}", onKeep, fill = null)
        }
    }
}
