package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.jakubfronczyk.northform.ui.theme.Theme

/**
 * The run flow's ring (`Countdown.dc.html`, `Cooldown.dc.html`): a [diameter] circle (measured on the stroke's centre
 * line, the svg's `r`) in `Theme.track`, and over it the lit [fraction] in the accent, from 12 o'clock clockwise with
 * round ends. Nothing lit at 0.
 */
@Composable
fun ProgressRing(fraction: Float, diameter: Dp, stroke: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(diameter + stroke)) {
        val line = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
        val inset = stroke.toPx() / 2
        val arc = Size(size.width - stroke.toPx(), size.height - stroke.toPx())
        val topLeft = Offset(inset, inset)
        drawArc(Theme.track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke.toPx()))
        val lit = fraction.coerceIn(0f, 1f)
        if (lit > 0f) drawArc(Theme.accent, startAngle = -90f, sweepAngle = 360f * lit, useCenter = false, topLeft = topLeft, size = arc, style = line)
    }
}
