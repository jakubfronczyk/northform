package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
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
    /** The "Your run" card's values, owned by the session (History's Review mode passes the stored ones with no [onEdit]). */
    edits: SummaryEdits,
    onEdit: (SummaryEdits) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    /** SU6: the HRmax card's Update. */
    onAcceptHrMax: () -> Unit = {},
    /** Failed's Retry. */
    onRetry: () -> Unit = {},
    /** The platform map (D13), as the run flow injects it; it reads [LocalMapCamera], provided here fitted to the route. */
    map: @Composable (route: Route) -> Unit = { route -> RouteCanvas(route) },
    /**
     * history-screen/03: a saved recording reopened from Today or History (`SummaryReview`): a Back control, the
     * "Your run" block read-only, no HRmax card, no Save. The bin and its sheet stay.
     */
    review: Boolean = false,
    /** Back, in [review]. */
    onBack: () -> Unit = {},
) {
    when (val s = state) {
        SummarySession.State.Computing -> Box(Modifier.fillMaxSize().background(Theme.bg), contentAlignment = Alignment.Center) {
            Text("Computing…", style = Type.secondaryText, color = Theme.text2)
        }
        is SummarySession.State.Ready -> SummaryContent(SummaryModel.from(s.summary), edits, onEdit, error = s.error, busy = s.busy, onSave = onSave, onDelete = onDelete, onAcceptHrMax = onAcceptHrMax, map = map, onBack = onBack.takeIf { review })
        is SummarySession.State.Failed -> FailedContent(s.reason, noun = s.type?.let(SummaryModel::noun) ?: "recording", busy = s.busy, onRetry = onRetry, onDelete = onDelete, onBack = onBack.takeIf { review })
        SummarySession.State.Saved, SummarySession.State.Deleted -> Unit // the root removes the screen
    }
}

/** "Couldn't compute this run": Retry, or Delete through the same confirm sheet (summary-screen/05). Never a silent Today. */
@Composable
private fun FailedContent(reason: String, noun: String, busy: Boolean, onRetry: () -> Unit, onDelete: () -> Unit, onBack: (() -> Unit)?) {
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text("Couldn't compute this $noun", style = Type.screenTitle, color = Theme.text)
            Spacer(Modifier.height(8.dp))
            Text(reason, style = Type.secondaryText, color = Theme.text3)
            Spacer(Modifier.weight(1f))
            BigButton("Retry", onRetry, enabled = !busy)
            Spacer(Modifier.height(12.dp))
            BigButton("Delete $noun", { confirmDelete = true }, fill = null, enabled = !busy)
        }
        onBack?.let { BackButton(it) }
        if (confirmDelete) DeleteConfirmSheet(what = noun, detail = "Nothing could be computed from it. This can't be undone.", onKeep = { confirmDelete = false }, onDelete = { confirmDelete = false; onDelete() })
    }
}

@Composable
private fun SummaryContent(m: SummaryModel, edits: SummaryEdits, onEdit: (SummaryEdits) -> Unit, error: String?, busy: Boolean, onSave: () -> Unit, onDelete: () -> Unit, onAcceptHrMax: () -> Unit, map: @Composable (route: Route) -> Unit, onBack: (() -> Unit)?) {
    val review = onBack != null
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

            if (m.hrMaxSuggestion != null && !review) {
                HrMaxCard(m.hrMaxSuggestion, m.hrMaxCurrent, enabled = !busy, onAccept = onAcceptHrMax)
                Spacer(Modifier.height(16.dp))
            }
            HeartRateCard(m)
            Spacer(Modifier.height(16.dp))
            SplitsCard(m)
            Spacer(Modifier.height(16.dp))
            EditsCard(m, edits, onEdit, readOnly = review)
            Spacer(Modifier.height(24.dp))

            if (error != null) {
                Text("Couldn't ${if (review) "delete" else "save"}: $error", style = Type.secondaryText, color = Theme.warn)
                Spacer(Modifier.height(12.dp))
            }
            if (!review) BigButton("Save ${m.typeLabel.lowercase()}", enabled = !busy, onClick = onSave)
            }
        }
        onBack?.let { BackButton(it) }
        // `DeleteConfirm.dc.html`: "Tempo · 10.21 km · 30 Sep. This can't be undone."
        if (confirmDelete) DeleteConfirmSheet(what = m.typeLabel.lowercase(), detail = "${edits.tag?.name ?: m.typeLabel} · ${m.distance} km · ${m.dateLine.substringBefore(" ·")}. This can't be undone.", onKeep = { confirmDelete = false }, onDelete = { confirmDelete = false; onDelete() })
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

/** The "Your run" card: Type chips (a run only), "How hard did it feel?" 0–10 in two rows, Notes. [readOnly]: what was saved, no taps. */
@Composable
private fun EditsCard(m: SummaryModel, edits: SummaryEdits, onEdit: (SummaryEdits) -> Unit, readOnly: Boolean = false) {
    val tag = edits.tag
    val rpe = edits.rpe
    val notes = edits.notes ?: ""
    val onTag = { t: RunTag? -> onEdit(edits.copy(tag = t)) }
    val onRpe = { n: Int? -> onEdit(edits.copy(rpe = n)) }
    val onNotes = { text: String -> onEdit(edits.copy(notes = text.ifEmpty { null })) }
    Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(m.yourLabel, style = Type.sheetTitle, color = Theme.text)
        if (m.showsTags) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type", style = Type.tertiaryText, color = Theme.text2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in RunTag.entries) OptionPill(t.name, selected = t == tag, modifier = Modifier.weight(1f), enabled = !readOnly) { onTag(if (t == tag) null else t) }
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
                    for (n in row) OptionPill("$n", selected = n == rpe, modifier = Modifier.weight(1f), enabled = !readOnly) { onRpe(if (n == rpe) null else n) }
                    if (row.first == 6) Spacer(Modifier.weight(1f))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notes", style = Type.tertiaryText, color = Theme.text2)
            Box(Modifier.fillMaxWidth().background(Theme.bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (notes.isEmpty()) Text(if (readOnly) "No notes" else "Legs heavy after km 7", style = Type.secondaryText, color = Theme.text3)
                BasicTextField(notes, onNotes, readOnly = readOnly, textStyle = Type.secondaryText.copy(color = Theme.text), cursorBrush = SolidColor(Theme.accent), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** One selectable pill of the Type row and the RPE grid: filled in the accent when selected, outlined otherwise. */
@Composable
private fun OptionPill(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val framed = if (selected) modifier.background(Theme.accent, shape) else modifier.border(1.dp, Theme.line, shape)
    Box(framed.height(44.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
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
private fun DeleteConfirmSheet(what: String, detail: String, onKeep: () -> Unit, onDelete: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onKeep)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Theme.card, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 50.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Delete this $what?", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black), color = Theme.text)
            Text(detail, style = Type.secondaryText, color = Theme.text2)
            BigButton("Delete for good", onDelete, fill = Color(0xFFB83A2E))
            BigButton("Keep $what", onKeep, fill = null)
        }
    }
}

/** history-screen/03: Back on a reopened Summary, over the map's top-left corner as on the gate. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 16.dp, top = 64.dp).size(44.dp).background(Theme.bg.copy(alpha = 0.85f), CircleShape).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("‹", style = Type.number22, color = Theme.text) }
}
