package com.jakubfronczyk.northform.ui.crash

import androidx.compose.ui.graphics.vector.ImageVector
import com.jakubfronczyk.northform.ui.NfIcons
import com.jakubfronczyk.northform.ui.NfIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jakubfronczyk.northform.core.RecordingState
import com.jakubfronczyk.northform.engine.run.CrashChoice
import com.jakubfronczyk.northform.engine.run.CrashFacts
import com.jakubfronczyk.northform.engine.run.CrashPrompt
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import com.jakubfronczyk.northform.ui.VDivider
import com.jakubfronczyk.northform.ui.ValueCell
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The R28 crash prompt (`design/canvas/project/CrashPrompt.dc.html`, `CrashPromptLate` = the same board
 * past 10 minutes): a scrim over Today and a bottom sheet with what is saved and the three answers.
 * Which answers are offered and which one leads is [CrashPrompt.of]'s call, not the screen's: it is asked again
 * at every minute boundary from the wall clock [now] (D30, [untilNextMinute]), so a prompt left up past 10 min turns to
 * Save-first and past 2 h drops Continue while it is on screen; the tap itself is re-checked by `CrashPrompt.resolve`.
 * The buttons keep one order ([inFixedOrder]); only the filled one moves. Delete asks twice (R27), in place.
 */
@Composable
fun CrashPromptScreen(facts: CrashFacts, now: () -> Instant, onChoice: (CrashChoice) -> Unit) {
    var at by remember { mutableStateOf(now()) }
    LaunchedEffect(facts) { while (true) { at = now(); delay(untilNextMinute(facts, at)) } }
    val prompt = CrashPrompt.of(facts, at)
    val minutesAgo = facts.interruptedFor(at).inWholeMinutes
    // The scrim takes every touch: Today's buttons under it stay out of reach until the prompt is answered.
    // A background alone has no pointer node, so hit-testing would fall through to the screen below. A no-op,
    // no-indication clickable is a hit target that yields to the sheet's own buttons (they consume the down first);
    // a pointerInput that consumed everything would instead cancel those buttons' taps in the Final pass.
    Box(Modifier.fillMaxSize().background(Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Theme.card, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 50.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NfIcon(NfIcons.interrupted, Theme.warn, size = 18.dp)
                    Text("Interrupted $minutesAgo min ago · run started ${RunFormat.localTime(facts.startedAt, facts.tzOffset)}", style = Type.statusLabel, color = Theme.warn)
                }
                Text("Your run was interrupted", style = SheetHeadline, color = Theme.text)
                Text(
                    if (facts.state == RecordingState.Active) "The app closed. This part is saved." else "The app closed after you stopped. This part is saved.",
                    style = Type.secondaryText, color = Theme.text2,
                )
            }

            Row {
                Fact("Distance", RunFormat.distance(facts.distance), unit = "km")
                FactDivider()
                Fact("Time", RunFormat.time(facts.totalTime))
                FactDivider()
                Fact("Saved until", RunFormat.localTime(facts.savedUntil, facts.tzOffset))
            }

            if (facts.state == RecordingState.Active) {
                HorizontalDivider(color = Theme.line)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Gap until now counts as sensor + GPS lost", style = GapTitle, color = Theme.text)
                    Text(
                        "${RunFormat.localTime(facts.savedUntil, facts.tzOffset)} to ${RunFormat.localTime(at, facts.tzOffset)} · total time keeps running",
                        style = Type.tertiaryText, color = Theme.text2,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var confirmDelete by remember { mutableStateOf(false) }
                for (choice in prompt.inFixedOrder()) when (choice) {
                    CrashChoice.Continue -> ChoiceButton("Continue run", main = prompt.main == choice, mainColors = Continue, icon = NfIcons.play) { onChoice(choice) }
                    CrashChoice.Save -> ChoiceButton("Save and finish", main = prompt.main == choice, mainColors = Save) { onChoice(choice) }
                    CrashChoice.Delete -> Text(
                        if (confirmDelete) "Tap again to delete this run" else "Delete run",
                        style = DeleteLabel, color = Danger,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                            .clickable { if (confirmDelete) onChoice(choice) else confirmDelete = true }
                            .padding(top = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A main action is filled (white for Continue, the primary orange for Save); the other is outlined. */
@Composable
private fun ChoiceButton(title: String, main: Boolean, mainColors: Pair<Color, Color>, icon: ImageVector? = null, action: () -> Unit) =
    BigButton(title, action, fill = mainColors.first.takeIf { main }, textColor = if (main) mainColors.second else Theme.text, icon = icon)

@Composable
private fun RowScope.Fact(label: String, value: String, unit: String? = null) =
    ValueCell(value, Modifier.weight(1f), labelText = label, unit = unit, unitGap = 3.dp, unitLift = 2.dp)

@Composable
private fun FactDivider() = VDivider(Modifier.padding(end = 12.dp).height(40.dp))

private val Scrim = Color(0xB8080809) // rgba(8,8,9,0.72)
private val Danger = Color(0xFFFF6961)
private val Continue = Theme.text to Theme.bg
private val Save = Theme.primary to Color.White
private val SheetHeadline = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black)
private val GapTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
private val DeleteLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

/**
 * The offered answers in the one order the screen draws them (D39: Garmin and Strava never swap buttons under the finger):
 * Continue (while offered) first, Save second, Delete last. Which one leads is shown by emphasis alone ([CrashPrompt.main]).
 */
internal fun CrashPrompt.inFixedOrder(): List<CrashChoice> = FixedOrder.filter { it in choices }

private val FixedOrder = listOf(CrashChoice.Continue, CrashChoice.Save, CrashChoice.Delete)

/**
 * How long until anything on the prompt can change: everything it shows is per minute, so the next redraw is the earlier of
 * the gap's next whole minute ("Interrupted N min ago", the 10 min / 2 h rules) and the local wall clock's ("to 07:42").
 * Never zero, so the redraw loop cannot spin.
 */
internal fun untilNextMinute(facts: CrashFacts, at: Instant): Duration {
    val minute = 60_000L
    val gapMs = (at - facts.savedUntil).inWholeMilliseconds
    val toGapMinute = if (gapMs < 0) -gapMs + minute else minute - gapMs.mod(minute)
    val toWallMinute = minute - (at.toEpochMilliseconds() + facts.tzOffset * 1000L).mod(minute)
    return minOf(toGapMinute, toWallMinute).milliseconds
}
