package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.ui.NfIcon
import com.jakubfronczyk.northform.ui.NfIcons
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.time.Instant

/**
 * The countdown (`Countdown.dc.html`, run-flow-artboards; `RunView.swift:56` was the bare number): ✕ and the type chip
 * on top, the ring with the seconds, "Put your phone in your pocket", five tick dots and "A buzz every second". It reads
 * the run's clock as each second turns ([untilNextSecond]); every value comes from [CountdownModel]. [cancel] is the flow's close: the run
 * ends with nothing recorded and is discarded (D116). The artboard's "Start now" is not drawn: it needs a `UserAction`
 * the frozen reducer does not have (the spec's Out of Scope).
 */
@Composable
fun CountdownScreen(endsAt: Instant, type: RecordingType, wallClock: WallClock, cancel: () -> Unit) {
    var now by remember { mutableStateOf(wallClock.now()) }
    LaunchedEffect(endsAt) {
        while (true) {
            now = wallClock.now()
            delay(untilNextSecond(endsAt, now)) // wake as the shown second changes: one model per second
        }
    }
    val model = CountdownModel.from(endsAt, now, type)
    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.gutter).padding(top = Dimens.screenTop, bottom = Dimens.screenBottom)) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.iconButton),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            // Nudged 10 dp left so the glyph lines up with the gutter, like BackButton.
            Box(Modifier.offset(x = (-10).dp).size(Dimens.iconButton).clickable(onClick = cancel), contentAlignment = Alignment.Center) {
                NfIcon(NfIcons.close, Theme.text, size = Dimens.icon, label = "Cancel start")
            }
            Chip(model.typeLabel, model.chipColor, background = Theme.card)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp).clearAndSetSemantics { contentDescription = model.accessibility },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(Dimens.countdownRingBox), contentAlignment = Alignment.Center) {
                ProgressRing(model.ring, Dimens.countdownRing, Dimens.countdownRingStroke)
                Text(model.number, style = Type.countdown, color = Theme.text)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(model.headline, style = Type.countdownHeadline, color = Theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(model.dots) { i ->
                        Box(Modifier.size(Dimens.tickDot).background(if (i < model.litDots) Theme.accent else Theme.unlit, CircleShape))
                    }
                }
                Text(model.hint, style = Type.tertiaryText, color = Theme.text2)
            }
        }
    }
}
