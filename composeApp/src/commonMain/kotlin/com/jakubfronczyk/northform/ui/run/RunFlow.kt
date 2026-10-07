package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.core.inSecondsDouble
import com.jakubfronczyk.northform.core.ports.WallClock
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Instant

/**
 * The whole run flow, switched by phase (`Features/Run/RunView.swift:7-36`). Reads ONLY the session
 * handle's state flows and decides nothing: every tap goes to the session stamped with the run's own
 * clock (`RunViewModel.swift:49-52`, D100). The map is injected because it is a platform view (D13).
 */
@Composable
fun RunFlow(session: RunSessionHandle, onClose: () -> Unit, map: @Composable (route: Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val snapshot by session.snapshots.collectAsState()
    val route by session.routes.collectAsState()
    val send: (UserAction) -> Unit = { action ->
        val tappedAt = session.wallClock.now() // the tap's time, on the run's clock, before any hop
        scope.launch { session.send(action, tappedAt) }
    }

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        when (val phase = snapshot.phase) {
            RunPhase.Gate -> GateScreen(start = { send(UserAction.Start) }, close = onClose)
            is RunPhase.Countdown -> CountdownScreen(phase.endsAt, session.wallClock)
            RunPhase.Active, is RunPhase.Paused -> LiveRunScreen(snapshot, route, send, map)
            is RunPhase.Cooldown -> StoppedScreen(snapshot, skip = { send(UserAction.SkipCooldown) })
            RunPhase.Done -> DoneScreen(snapshot, close = onClose)
        }
    }
}

/** The bare screens' shared frame: centred, a title block in the middle, the action at the bottom. */
@Composable
private fun BareScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** `RunView.swift:38`. */
@Composable
fun GateScreen(start: () -> Unit, close: () -> Unit) = BareScreen {
    Spacer(Modifier.weight(1f))
    Text("Ready to run", style = Type.screenTitle, color = Theme.text)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Start", start)
    Spacer(Modifier.height(8.dp))
    Text("Cancel", color = Theme.text2, modifier = Modifier.clickable(onClick = close).padding(12.dp))
}

/** `RunView.swift:56` — counts down on the run's clock, ten times a second; never shows 0. */
@Composable
fun CountdownScreen(endsAt: Instant, wallClock: WallClock) {
    var now by remember { mutableStateOf(wallClock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = wallClock.now()
            delay(100)
        }
    }
    val left = max(1, ceil((endsAt - now).inSecondsDouble).toInt())
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$left", style = Type.countdown, color = Theme.text)
    }
}

/** `RunView.swift:70`. */
@Composable
fun StoppedScreen(snapshot: RunSnapshot, skip: () -> Unit) = BareScreen {
    Spacer(Modifier.weight(1f))
    Text("Run stopped", style = Type.screenTitle, color = Theme.text)
    Text("${RunFormat.time(snapshot.totalTime)} · ${RunFormat.distance(snapshot.distance)} km", style = Type.number22, color = Theme.text2)
    Text("Cool-down: measuring heart-rate recovery", style = Type.secondaryText, color = Theme.text3)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Skip", skip)
}

/** `RunView.swift:89`. */
@Composable
fun DoneScreen(snapshot: RunSnapshot, close: () -> Unit) = BareScreen {
    Spacer(Modifier.weight(1f))
    Text("Saved", style = Type.screenTitle, color = Theme.text)
    Text("${RunFormat.distance(snapshot.distance)} km · ${RunFormat.time(snapshot.totalTime)}", style = Type.number22, color = Theme.text2)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Done", close)
}

/** `RunView.swift:107`. */
@Composable
fun PrimaryButton(title: String, action: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(68.dp)
            .background(Theme.primary, RoundedCornerShape(16.dp))
            .clickable(onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Type.sheetTitle, color = Theme.text)
    }
}
