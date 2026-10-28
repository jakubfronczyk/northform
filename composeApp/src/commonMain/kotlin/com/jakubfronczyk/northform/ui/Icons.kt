package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The artboards' icons (visual-polish spec), each built from the SVG path data the design draws it with: a 24 × 24
 * box, stroke 2 with round caps and joins (play is filled). The caller tints and sizes them through [NfIcon]. Circles
 * and rounded rectangles are written as the paths they draw, since an `ImageVector` takes paths only.
 */
object NfIcons {
    val back = stroked("M15 5l-7 7 7 7")
    val forward = stroked("M9 5l7 7-7 7")
    val check = stroked("M5 12.5l4.5 4.5L19 7.5")
    val warning = stroked("M12 3l10 18H2z", "M12 10v5", "M12 18h.01")
    val bin = stroked("M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    /** The heart-rate sensor with its pulse (Today's card, the Gate's heart-rate row). */
    val sensor = stroked(roundedRect(6f, 5f, 12f, 14f, 4f), "M9 12h1.5l1-2 1.5 4 1-2H15")
    val runner = stroked(circle(15f, 4.5f, 1.8f), "M9 21l3-6 3 2v4", "M6 12l3-4 4 1 2 4 3 1")
    val walker = stroked(circle(13f, 4.5f, 1.8f), "M10 21l2-6 2 2v4", "M9 13l1-5 3 1 2 3")
    val play: ImageVector = icon { addPath(addPathNodes("M7 4.5v15l13-7.5z"), fill = SolidColor(Color.Black)) }

    private val batteryFull = battery("M6 10.5v3M9.5 10.5v3M13 10.5v3")
    private val batteryLow = battery("M6 10.5v3")

    /** The Gate's battery: three bars, or one when [low]. */
    fun battery(low: Boolean) = if (low) batteryLow else batteryFull

    private fun battery(bars: String) = stroked(roundedRect(2.5f, 7f, 17f, 10f, 2.5f), "M22 10.5v3", bars)

    private fun stroked(vararg paths: String) = icon {
        for (d in paths) {
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }

    private fun icon(body: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(body).build()

    /** SVG `<circle>` as a path: two half arcs. */
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    /** SVG `<rect rx>` as a path. */
    private fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"
}

/** One of [NfIcons], tinted [color] at [size] (decorative: the text beside it carries the meaning). */
@Composable
fun NfIcon(icon: ImageVector, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription = null, tint = color, modifier = modifier.size(size))
