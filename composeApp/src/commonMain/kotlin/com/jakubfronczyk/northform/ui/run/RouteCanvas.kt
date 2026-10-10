package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.ui.Theme

/**
 * The route drawn by Compose, no MapKit: the fallback behind D13's "fail ⇒ Compose-drawn route" and
 * what previews and tests use (the platform map lives in `:app`). Reads [LocalMapCamera]: follows the
 * route end, a drag pans and stops following, `recenter()` snaps back. Only a changed route or camera
 * rebuilds the paths; the 1 Hz snapshot never reaches this composable.
 */
@Composable
fun RouteCanvas(route: Route, modifier: Modifier = Modifier) {
    val camera = LocalMapCamera.current
    val pan = camera.pan
    Canvas(
        modifier.fillMaxSize().background(RunPalette.mapPanel).pointerInput(Unit) {
            // Compose's drag primitive; `size` is the pointer-input node's size in pixels.
            detectDragGestures { change, drag ->
                change.consume()
                camera.dragBy(RouteProjection.dragToPan(drag.x, drag.y, size.width.toFloat(), size.height.toFloat()))
            }
        },
    ) {
        val projected = RouteProjection.project(route, size.width, size.height, pan)
        if (projected.isEmpty()) return@Canvas
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (segment in projected) {
            if (segment.size < 2) continue
            val path = Path()
            path.moveTo(segment.first().x, segment.first().y)
            for (p in segment.drop(1)) path.lineTo(p.x, p.y)
            drawPath(path, Theme.accent, style = stroke)
        }
        val start = projected.first().first()
        drawCircle(Theme.bg, 7.dp.toPx(), Offset(start.x, start.y))
        drawCircle(Theme.text, 7.dp.toPx(), Offset(start.x, start.y), style = Stroke(3.dp.toPx()))
        val end = projected.last().last()
        drawCircle(Theme.accent.copy(alpha = 0.22f), 20.dp.toPx(), Offset(end.x, end.y))
        drawCircle(Theme.accent, 9.dp.toPx(), Offset(end.x, end.y))
        drawCircle(Theme.bg, 9.dp.toPx(), Offset(end.x, end.y), style = Stroke(3.dp.toPx()))
    }
}
