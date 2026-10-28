package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type

/**
 * The zone editor (`ZoneEditor.dc.html`, profile-settings/04): each zone's start in % of heart-rate reserve with ±1
 * steppers and live bpm, Reset to default, Save. Lays out a [ZoneEditor]; Back discards.
 */
@Composable
fun ZoneEditorScreen(profile: Profile, onSave: (Profile) -> Unit, back: () -> Unit) {
    var editor by remember(profile) { mutableStateOf(ZoneEditor.of(profile)) }
    Column(
        Modifier.fillMaxSize().background(Theme.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.offset(x = (-10).dp).size(44.dp).clickable(onClick = back), contentAlignment = Alignment.Center) {
                NfIcon(NfIcons.back, Theme.text, size = 22.dp, label = "Back to Settings")
            }
            Box(Modifier.weight(1f))
            Text("Reset to default", style = Type.statusLabel.copy(fontSize = 15.sp), color = Theme.accent, modifier = Modifier.clickable { editor = editor.reset() }.padding(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Zones", style = Type.number(28, FontWeight.ExtraBold), color = Theme.text)
            Text("Where each zone starts, as % of heart rate reserve.", style = Type.secondaryText, color = Theme.text2)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Theme.card).padding(horizontal = 16.dp)) {
            editor.rows.forEachIndexed { i, row ->
                if (i > 0) DashedLine()
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(Theme.zoneColors[i + 1], CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.label, style = Type.statusLabel.copy(fontSize = 15.sp), color = Theme.text)
                        Text("from ${row.bpm} bpm", style = Type.tertiaryText, color = Theme.text2)
                    }
                    Stepper("−", "Lower Z${i + 1} start", row.canLower) { editor = editor.lower(i) }
                    Text("${row.percent} %", style = Type.number(17, FontWeight.Bold), color = Theme.text)
                    Stepper("+", "Raise Z${i + 1} start", row.canRaise) { editor = editor.raise(i) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            editor.bar.forEachIndexed { zone, weight -> Box(Modifier.weight(weight.toFloat()).fillMaxHeight().background(Theme.zoneColors[zone])) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(editor.preview, style = Type.tertiaryText, color = Theme.text2)
            Text("Karvonen · stored as % so bpm follow your max", style = Type.tertiaryText.copy(fontSize = 12.sp), color = Theme.text3)
        }
        BigButton("Save", { onSave(profile.copy(zones = editor.zones)) })
    }
}

/** A 36 dp round ±1 button; off (dim, no tap) at its limit. */
@Composable
private fun Stepper(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, if (enabled) Theme.outline else Theme.track, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).clearAndSetSemantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, style = Type.number(20, FontWeight.Bold), color = if (enabled) Theme.text else Theme.dim) }
}
