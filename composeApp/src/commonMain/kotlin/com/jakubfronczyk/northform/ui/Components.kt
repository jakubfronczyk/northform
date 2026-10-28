package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type

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
    /** A 20 dp icon before [title] (Start, Resume: the artboards' play), tinted like the text. */
    icon: ImageVector? = null,
) {
    val framed = if (fill != null) modifier.background(fill, Shapes.bigButton) else modifier.border(Dimens.stroke, Theme.ring, Shapes.bigButton)
    Box(
        framed.height(Dimens.bigButton).clip(Shapes.bigButton).clickable(enabled = enabled, onClick = onClick).padding(horizontal = contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon?.let { NfIcon(it, textColor, size = 20.dp) }
            Text(title, style = textStyle, color = textColor)
        }
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

/** The 1 dp solid rule between a card's groups (`SetupProfile.dc.html`). */
@Composable
fun HDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.line))

/**
 * A page's Back (`‹` in a 44 dp tap target, nudged 10 dp left so the glyph lines up with the gutter). [label] says where
 * it goes for VoiceOver: "Back to Settings", "Back to Today", or plain "Back" between Setup's steps.
 */
@Composable
fun BackButton(label: String, onClick: () -> Unit) {
    Box(Modifier.offset(x = (-10).dp).size(Dimens.iconButton).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        NfIcon(NfIcons.back, Theme.text, size = Dimens.icon, label = label)
    }
}

/**
 * A small button inside a card row (`Shapes.inlineButton`, 44 dp): [filled] in the primary (Allow, Pair), or outlined
 * (Open Settings).
 */
@Composable
fun InlineButton(text: String, onClick: () -> Unit, filled: Boolean = true) {
    val framed = if (filled) Modifier.background(Theme.primary, Shapes.inlineButton) else Modifier.border(Dimens.stroke, Theme.outline, Shapes.inlineButton)
    Box(
        framed.height(Dimens.inlineButton).clip(Shapes.inlineButton).clickable(onClick = onClick).padding(horizontal = if (filled) 18.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.secondaryBold, color = if (filled) Theme.onPrimary else Theme.text, maxLines = 1) }
}

/** The turning three-quarter ring beside "Scanning…" (`SetupPair.dc.html`), in the accent. */
@Composable
fun Spinner() {
    val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MILLIS, easing = LinearEasing)))
    NfIcon(NfIcons.spinner, Theme.accent, size = Dimens.iconSmall, modifier = Modifier.rotate(turn))
}

/** One turn of [Spinner]. */
private const val SPIN_MILLIS = 900

/** The 1 dp dashed rule (4 on, 4 off) between card rows. */
@Composable
fun DashedLine() = Canvas(Modifier.fillMaxWidth().height(1.dp)) {
    drawLine(Theme.line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
}

/**
 * The shared destructive confirm (`DeleteConfirm.dc.html`): a sheet over a dimmed screen with [title], [detail], the
 * red [confirm] and an outlined [keep]. Tapping outside keeps. Summary's Delete and Settings' Forget sensor use it.
 */
@Composable
fun ConfirmSheet(title: String, detail: String, confirm: String, keep: String, onKeep: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Theme.scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onKeep)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Theme.card, Shapes.sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = Dimens.gutter, end = Dimens.gutter, top = 24.dp, bottom = Dimens.screenBottom),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(title, style = Type.sheetHeadline, color = Theme.text)
            Text(detail, style = Type.secondaryText, color = Theme.text2)
            BigButton(confirm, onConfirm, fill = Theme.destructive)
            BigButton(keep, onKeep, fill = null)
        }
    }
}

/**
 * A screen whose content fits the phone when it can (Jakub's rule: no scrolling if it can be squeezed in) with its main
 * [button] pinned at the bottom. The content scrolls only when it truly can't fit (the keyboard up, a smaller phone).
 */
@Composable
fun PinnedScreen(button: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Theme.bg).padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop, bottom = Dimens.screenBottom)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.blockGap), content = content)
        Spacer(Modifier.height(Dimens.buttonGap))
        button()
    }
}
