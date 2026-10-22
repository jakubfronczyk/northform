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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.ui.Theme

/**
 * The route drawn by Compose, no MapKit: the fallback behind D13's "fail ⇒ Compose-drawn route" and
 * what previews and tests use (the platform map lives in `:app`). Reads [LocalMapCamera]: follows the
 * route end, a drag pans and stops following, `recenter()` snaps back. The route is projected to
 * metres and built into one path only when it changes; the view size and the pan are a draw-time
 * transform, so a drag frame allocates nothing. The 1 Hz snapshot never reaches this composable.
 */
@Composable
fun RouteCanvas(route: Route, modifier: Modifier = Modifier) {
    val camera = LocalMapCamera.current
    val metres = remember(route) { RouteProjection.metres(route) }
    // The path in metres from the route end, y = south so one uniform scale maps it to pixels.
    val path = remember(metres) {
        Path().apply {
            for (segment in metres) {
                if (segment.size < 2) continue
                moveTo(segment.first().eastMetres.toFloat(), -segment.first().northMetres.toFloat())
                for (i in 1 until segment.size) lineTo(segment[i].eastMetres.toFloat(), -segment[i].northMetres.toFloat())
            }
        }
    }
    Canvas(
        modifier.fillMaxSize().background(RunPalette.mapPanel).pointerInput(Unit) {
            // Compose's drag primitive; `size` is the pointer-input node's size in pixels.
            detectDragGestures { change, drag ->
                change.consume()
                camera.dragBy(RouteProjection.dragToPan(drag.x, drag.y, size.width.toFloat(), size.height.toFloat()))
            }
        },
    ) {
        if (metres.isEmpty()) return@Canvas
        // A fitted camera (the Summary) looks at the whole route's middle; the live one at the route end plus the drag.
        val fit = if (camera.fitted) RouteProjection.fit(metres) else null
        val pan = if (fit != null) fit.centre + camera.pan else camera.pan
        val mpp = (if (fit != null) fit.spanMetres / minOf(size.width, size.height).coerceAtLeast(1f) else RouteProjection.metresPerPixel(size.width, size.height)).toFloat()
        // The route end (metres 0,0) sits at the middle moved against the pan; then metres → pixels.
        translate(size.width / 2 - (pan.eastMetres / mpp).toFloat(), size.height / 2 + (pan.northMetres / mpp).toFloat()) {
            scale(1 / mpp, pivot = Offset.Zero) {
                drawPath(path, Theme.accent, style = Stroke(width = 5.dp.toPx() * mpp, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val start = RouteProjection.toPx(metres.first().first(), size.width, size.height, pan)
        drawCircle(Theme.bg, 7.dp.toPx(), Offset(start.x, start.y))
        drawCircle(Theme.text, 7.dp.toPx(), Offset(start.x, start.y), style = Stroke(3.dp.toPx()))
        val end = RouteProjection.toPx(metres.last().last(), size.width, size.height, pan)
        drawCircle(Theme.accent.copy(alpha = 0.22f), 20.dp.toPx(), Offset(end.x, end.y))
        drawCircle(Theme.accent, 9.dp.toPx(), Offset(end.x, end.y))
        drawCircle(Theme.bg, 9.dp.toPx(), Offset(end.x, end.y), style = Stroke(3.dp.toPx()))
    }
}
