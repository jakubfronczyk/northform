package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import com.jakubfronczyk.northform.ui.ValueCell

// The Summary's "Heart rate" card and "Splits" table (summary-screen/03), drawn from [SummaryModel] only.

/** Average / Max / "1 min after stop", the line over the zone bands with its gap labels, "Total per zone". */
@Composable
internal fun HeartRateCard(m: SummaryModel) {
    SummaryCard("Heart rate") {
        Row(Modifier.fillMaxWidth()) {
            ValueCell(m.avgHr, Modifier.weight(1f), labelText = "Average", unit = "bpm")
            ValueCell(m.hrMax, Modifier.weight(1f), labelText = "Max", unit = "bpm")
            ValueCell(m.recovery, Modifier.weight(1f), labelText = "1 min after stop", footer = { Text(m.recoveryDetail, style = Type.tertiaryText, color = Theme.text3) })
        }
        if (m.linePoints.isNotEmpty()) HrChart(m, Modifier.fillMaxWidth().height(150.dp))
        for (gap in m.gapLabels) gap.label?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Total per zone", style = Type.tertiaryText, color = Theme.text2)
            for (z in m.zones) ZoneBar(z)
        }
    }
}

/**
 * The line over the bands, on a plain Canvas (no chart library, D13's KoalaPlot left as the fallback): x is
 * seconds over the effort, y is bpm over [SummaryModel.bpmRange]; a gap breaks the line and is hatched.
 */
@Composable
private fun HrChart(m: SummaryModel, modifier: Modifier) {
    val range = m.bpmRange
    val span = (range.last - range.first).coerceAtLeast(1)
    Canvas(modifier) {
        fun x(seconds: Double) = (seconds / m.effortSeconds.coerceAtLeast(1.0) * size.width).toFloat()
        fun y(bpm: Int) = size.height - (bpm - range.first).toFloat() / span * size.height
        for (band in m.bands) {
            val top = y(band.highBpm.coerceIn(range))
            val bottom = y(band.lowBpm.coerceIn(range))
            drawRect(Theme.zoneColors[band.zone].copy(alpha = 0.18f), topLeft = Offset(0f, top), size = Size(size.width, (bottom - top).coerceAtLeast(0f)))
        }
        for (gap in m.gapLabels) {
            drawRect(Theme.bg.copy(alpha = 0.55f), topLeft = Offset(x(gap.fromSeconds), 0f), size = Size(x(gap.untilSeconds) - x(gap.fromSeconds), size.height))
        }
        // One sub-path per stretch between gaps: the line never bridges a gap.
        val path = Path()
        var open = false
        var cut = 0
        for (p in m.linePoints) {
            while (cut < m.gapLabels.size && m.gapLabels[cut].untilSeconds <= p.seconds) { cut++; open = false }
            val inGap = cut < m.gapLabels.size && p.seconds >= m.gapLabels[cut].fromSeconds
            if (inGap) { open = false; continue }
            if (open) path.lineTo(x(p.seconds), y(p.bpm)) else path.moveTo(x(p.seconds), y(p.bpm))
            open = true
        }
        drawPath(path, Theme.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ZoneBar(z: SummaryModel.ZoneRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(z.label, style = Type.statusLabel, color = Theme.zoneColors[z.zone], modifier = Modifier.width(28.dp))
        Box(Modifier.weight(1f).height(10.dp).background(Theme.track, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(z.fraction).background(Theme.zoneColors[z.zone], RoundedCornerShape(5.dp)))
        }
        Text(z.time, style = Type.tertiaryText, color = Theme.text2, modifier = Modifier.width(52.dp))
    }
}

/** KM · PACE (bar, the fastest km full) · HR · ELEV; the partial last km in italics. */
@Composable
internal fun SplitsCard(m: SummaryModel) {
    SummaryCard("Splits") {
        Row(Modifier.fillMaxWidth()) {
            for ((title, w) in listOf("KM" to 0.5f, "PACE" to 2f, "HR" to 0.8f, "ELEV" to 1.1f)) Text(title, style = Type.tertiaryText, color = Theme.text3, modifier = Modifier.weight(w))
        }
        HorizontalDivider(color = Theme.line)
        for (row in m.splits) {
            val style = if (row.partial) Type.rowLabel.copy(fontStyle = FontStyle.Italic) else Type.rowLabel
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.km, style = style, color = Theme.text, modifier = Modifier.weight(0.5f))
                Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(8.dp).background(Theme.track, RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(row.paceFraction).background(Theme.accent, RoundedCornerShape(4.dp)))
                    }
                    Text(row.pace, style = style, color = Theme.text, modifier = Modifier.width(44.dp))
                }
                Text(row.hr, style = style, color = Theme.text2, modifier = Modifier.weight(0.8f))
                Text(row.elev, style = style, color = Theme.text2, modifier = Modifier.weight(1.1f))
            }
        }
    }
}

/** The card frame every Summary section shares. */
@Composable
internal fun SummaryCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = Type.sheetTitle, color = Theme.text)
        content()
    }
}
