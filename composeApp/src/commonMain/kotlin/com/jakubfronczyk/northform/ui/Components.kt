package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BigButtonShape = RoundedCornerShape(16.dp)

/**
 * The one 68 dp action button of every artboard (`RunView.swift:107`): filled with [fill], or outlined
 * in `Theme.ring` when [fill] is null. Size comes from [modifier] (full width, or a weight in a row).
 */
@Composable
fun BigButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    fill: Color? = Theme.primary,
    textColor: Color = Theme.text,
    textStyle: TextStyle = Type.sheetTitle,
    enabled: Boolean = true,
    contentPadding: Dp = 0.dp,
) {
    val framed = if (fill != null) modifier.background(fill, BigButtonShape) else modifier.border(1.5.dp, Theme.ring, BigButtonShape)
    Box(
        framed.height(68.dp).clip(BigButtonShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = textStyle, color = textColor)
    }
}

/**
 * A number with its label above and unit beside it (the crash facts, the live grid and small row, the
 * sensor stats, the gate values). Every screen keeps its own sizes through the parameters; [label]
 * replaces the plain [labelText] when a cell needs more than words (the HR zone pill), [footer] adds a
 * line under the number.
 */
@Composable
fun ValueCell(
    value: String,
    modifier: Modifier = Modifier,
    labelText: String? = null,
    unit: String? = null,
    valueStyle: TextStyle = Type.number24,
    color: Color = Theme.text,
    unitStyle: TextStyle = Type.tertiaryText,
    unitGap: Dp = 4.dp,
    unitLift: Dp = 3.dp,
    spacing: Dp = 2.dp,
    maxLines: Int = Int.MAX_VALUE,
    label: (@Composable () -> Unit)? = labelText?.let { { Text(it, style = Type.tertiaryText, color = Theme.text2) } },
    footer: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        label?.invoke()
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = valueStyle, color = color, maxLines = maxLines)
            if (unit != null) {
                Spacer(Modifier.width(unitGap))
                Text(unit, style = unitStyle, color = Theme.text2, modifier = Modifier.padding(bottom = unitLift))
            }
        }
        footer?.invoke()
    }
}

/** The 1 dp vertical rule between value cells; [modifier] sets its height and spacing. */
@Composable
fun VDivider(modifier: Modifier) = Box(modifier.width(1.dp).background(Theme.line))

/** The 1 dp dashed rule (4 on, 4 off) between card rows. */
@Composable
fun DashedLine() = Canvas(Modifier.fillMaxWidth().height(1.dp)) {
    drawLine(Theme.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
}
