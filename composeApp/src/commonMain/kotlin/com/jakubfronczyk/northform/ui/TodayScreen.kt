package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType

private val CardShape = RoundedCornerShape(22.dp)

/**
 * The Today artboard (`Today.dc.html`, today-screen spec): the date, the sensor card, the Run and Walk tiles, the
 * latest saved recording, Recent and "See all history". Lays out a [TodayModel]; decides nothing. The card, row and
 * history taps are History's (app-ui/02) and do nothing until it lands.
 */
@Composable
fun TodayScreen(
    model: TodayModel,
    start: (RecordingType) -> Unit,
    openSensor: () -> Unit,
    openRecording: (RecordingId) -> Unit = {},
    openHistory: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.date, style = Type.rowLabel, color = Theme.text2)
            Text("Today", style = Type.homeTitle, color = Theme.text)
        }
        SensorCard(model.sensor, openSensor)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Run", fill = Theme.primary, sub = Color.White, Modifier.weight(1f)) { start(RecordingType.Run) }
            Tile("Walk", fill = null, sub = Theme.text2, Modifier.weight(1f)) { start(RecordingType.Walk) }
        }
        model.last?.let { LastCard(it) { openRecording(it.id) } }
        model.emptyLine?.let { Text(it, style = Type.secondaryText, color = Theme.text2) }
        if (model.showsHistory) {
            Column {
                if (model.recent.isNotEmpty()) {
                    Text("Recent", style = Type.sheetTitle.copy(fontSize = 17.sp), color = Theme.text, modifier = Modifier.padding(bottom = 6.dp))
                }
                for (row in model.recent) {
                    DashedLine()
                    RecentRow(row) { openRecording(row.id) }
                }
                if (model.recent.isNotEmpty()) DashedLine()
                Box(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = openHistory), contentAlignment = Alignment.Center) {
                    Text("See all history  ›", style = Type.statusLabel.copy(fontSize = 15.sp), color = Theme.accent)
                }
            }
        }
    }
}

/** "Verity Sense" with a green or amber dot and status; tap → the Sensor sheet. */
@Composable
private fun SensorCard(sensor: TodayModel.Sensor, onClick: () -> Unit) {
    val color = if (sensor.connected) Theme.ok else Theme.warn
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(CardShape).background(Theme.card).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(Theme.bg, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).border(2.dp, color, RoundedCornerShape(4.dp)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sensor.name, style = Type.sheetTitle.copy(fontSize = 16.sp), color = Theme.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(sensor.status, style = Type.tertiaryText, color = color)
            }
        }
        Chevron()
    }
}

/** A start tile: Run filled in the primary colour, Walk outlined. */
@Composable
private fun Tile(title: String, fill: Color?, sub: Color, modifier: Modifier, onClick: () -> Unit) {
    val framed = if (fill != null) modifier.background(fill, CardShape) else modifier.background(Theme.card, CardShape).border(1.5.dp, Theme.ring, CardShape)
    Column(
        framed.height(150.dp).clip(CardShape).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(title, style = Type.number(26, FontWeight.ExtraBold), color = if (fill != null) Color.White else Theme.text)
        Text("GPS + heart rate", style = Type.tertiaryText, color = sub)
    }
}

@Composable
private fun LastCard(card: TodayModel.Card, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(Theme.card).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(76.dp).background(Color(0xFF1D1F21), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            RouteGlyph(Theme.of(card.type))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.label, style = Type.tertiaryText, color = Theme.text2)
            Text(km(card.distance, 14), style = Type.number(28, FontWeight.ExtraBold), color = Theme.text)
            Text(card.detail, style = Type.rowLabel, color = Theme.text2)
        }
        Chevron()
    }
}

@Composable
private fun RecentRow(row: TodayModel.Row, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(Theme.of(row.type), CircleShape))
                Text(row.title, style = Type.statusLabel.copy(fontSize = 16.sp), color = Theme.text)
            }
            Text(row.date, style = Type.tertiaryText, color = Theme.text2)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(km(row.distance, 13), style = Type.number22, color = Theme.text)
            Text(row.detail, style = Type.tertiaryText, color = Theme.text2)
        }
        Chevron(Theme.text3)
    }
}

/** "10.21 km": the unit smaller and dimmer, on the number's baseline. */
private fun km(distance: String, unitSize: Int) = buildAnnotatedString {
    append(distance)
    withStyle(SpanStyle(fontSize = unitSize.sp, fontWeight = FontWeight.Medium, color = Theme.text2)) { append(" km") }
}

@Composable
private fun Chevron(color: Color = Theme.text2) = Text("›", style = Type.sheetTitle.copy(fontSize = 22.sp), color = color)

/** The card's thumbnail: a generic loop, not this recording's route (the route map is the Summary's). */
@Composable
private fun RouteGlyph(color: Color) = Canvas(Modifier.size(60.dp)) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0.24f * w, 0.88f * h)
        cubicTo(0.12f * w, 0.75f * h, 0.2f * w, 0.62f * h, 0.31f * w, 0.6f * h)
        cubicTo(0.45f * w, 0.55f * h, 0.42f * w, 0.42f * h, 0.4f * w, 0.4f * h)
        cubicTo(0.5f * w, 0.32f * h, 0.62f * w, 0.33f * h, 0.66f * w, 0.36f * h)
        cubicTo(0.84f * w, 0.47f * h, 0.74f * w, 0.6f * h, 0.68f * w, 0.66f * h)
        cubicTo(0.72f * w, 0.78f * h, 0.56f * w, 0.92f * h, 0.42f * w, 0.88f * h)
        cubicTo(0.32f * w, 0.92f * h, 0.27f * w, 0.91f * h, 0.24f * w, 0.88f * h)
    }
    drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    drawCircle(color, radius = 3.dp.toPx(), center = Offset(0.24f * w, 0.88f * h))
}
