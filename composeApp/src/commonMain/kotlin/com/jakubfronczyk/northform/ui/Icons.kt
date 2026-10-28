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
 * The artboards' icons (visual-polish spec), each built from the SVG path data and stroke width the design draws it
 * with at that spot: a 24 × 24 box, round caps and joins (play is filled). The caller tints and sizes them through
 * [NfIcon]. Circles and rounded rectangles are written as the paths they draw, since an `ImageVector` takes paths only.
 */
object NfIcons {
    /** History's and the reopened Summary's Back. */
    val back = stroked(2.4f, "M15 5l-7 7 7 7")
    /** The Gate's Back over the map. */
    val gateBack = stroked(2.2f, "M15 6l-6 6 6 6")
    /** A card's chevron (Today's sensor card and last-recording card). */
    val forward = stroked(2.2f, "M9 5l7 7-7 7")
    /** A list row's smaller chevron (Today's Recent, History). */
    val rowChevron = stroked(2.2f, "M9 6l6 6-6 6")
    /** The tick on the Gate's green disc. */
    val check = stroked(3f, "M5 12.5l4.5 4.5L19 7.5")
    /** The green tick beside "Allowed" and an OK fact (`SetupPermissions.dc.html`, `SetupPair.dc.html`). */
    val tick = stroked(2.4f, "M5 12.5l4.5 4.5L19 7.5")
    val warning = stroked(2f, "M12 3l10 18H2z", "M12 10v5", "M12 18h.01")
    /** The birth-date control's calendar (`SetupProfile.dc.html`). */
    val calendar = stroked(2f, roundedRect(3.5f, 5f, 17f, 15f, 3f), "M3.5 10h17", "M8 3v4", "M16 3v4")
    /** Setup's Bluetooth permission row. */
    val bluetooth = stroked(2f, "M7 7l10 10-5 5V2l5 5L7 17")
    /** Setup's Location permission row. */
    val location = stroked(2f, "M12 21s-6.5-6.2-6.5-11a6.5 6.5 0 0 1 13 0c0 4.8-6.5 11-6.5 11z", circle(12f, 10f, 2.4f))
    /** The three-quarter ring beside "Scanning…" (`SetupPair.dc.html`); the caller turns it. */
    val spinner = stroked(2.4f, "M12 3a9 9 0 1 0 9 9")
    /** The crash prompt's "Interrupted" line. */
    val interrupted = stroked(2.2f, "M12 3.5L2.5 20h19z", "M12 10v4.5", "M12 17.5v.01")
    val bin = stroked(2f, "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3")
    /** The heart-rate sensor with its pulse (Today's card, the Gate's heart-rate row). */
    val sensor = stroked(2f, roundedRect(6f, 5f, 12f, 14f, 4f), "M9 12h1.5l1-2 1.5 4 1-2H15")
    val runner = stroked(2f, circle(15f, 4.5f, 1.8f), "M9 21l3-6 3 2v4", "M6 12l3-4 4 1 2 4 3 1")
    val walker = stroked(2f, circle(13f, 4.5f, 1.8f), "M10 21l2-6 2 2v4", "M9 13l1-5 3 1 2 3")
    /** Today's Settings button. */
    val gear = stroked(2f, circle(12f, 12f, 3f), "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val play: ImageVector = icon { addPath(addPathNodes("M7 4.5v15l13-7.5z"), fill = SolidColor(Color.Black)) }

    private val batteryFull = battery("M6 10.5v3M9.5 10.5v3M13 10.5v3")
    private val batteryLow = battery("M6 10.5v3")

    /** The Gate's battery: three bars, or one when [low]. */
    fun battery(low: Boolean) = if (low) batteryLow else batteryFull

    private fun battery(bars: String) = stroked(2f, roundedRect(2.5f, 7f, 17f, 10f, 2.5f), "M22 10.5v3", bars)

    private fun stroked(width: Float, vararg paths: String) = icon {
        for (d in paths) {
            addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
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

/**
 * One of [NfIcons], tinted [color] at [size]. Decorative by default, when the text beside it carries the meaning; give
 * [label] when the icon is all a control shows (Back, the bin), so VoiceOver can say what it does.
 */
@Composable
fun NfIcon(icon: ImageVector, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier, label: String? = null) =
    Icon(icon, contentDescription = label, tint = color, modifier = modifier.size(size))
