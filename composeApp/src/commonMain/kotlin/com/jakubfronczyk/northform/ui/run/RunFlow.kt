package com.jakubfronczyk.northform.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jakubfronczyk.northform.core.UserAction
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunPhase
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.engine.run.RunSnapshot
import com.jakubfronczyk.northform.ui.BigButton
import com.jakubfronczyk.northform.ui.RunFormat
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlinx.coroutines.launch
import com.jakubfronczyk.northform.ui.theme.Dimens

/**
 * The whole run flow, switched by phase (`Features/Run/RunView.swift:7-36`). Reads ONLY the session
 * handle's state flows and decides nothing: every tap goes to the session stamped with the run's own
 * clock (`RunViewModel.swift:49-52`, D100). The map is injected because it is a platform view (D13).
 */
@Composable
fun RunFlow(
    session: RunSessionHandle,
    onClose: () -> Unit,
    map: @Composable (route: Route) -> Unit,
    /** E1: the sensor's connection + battery for the gate; null = unknown (previews, replay). */
    gateSensor: GateSensor?,
    /** The gate's "Connect" next to a sensor that is not connected; null hides it. */
    connectSensor: (() -> Unit)?,
    /** The gate's "Pair a sensor" when nothing is paired: opens the Sensor screen; null hides it. */
    pairSensor: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snapshot by session.snapshots.collectAsState()
    val route by session.routes.collectAsState()
    // Remembered so the screens below see the same lambda on every 1 Hz snapshot and can skip.
    val send: (UserAction) -> Unit = remember(session, scope) {
        { action ->
            val tappedAt = session.wallClock.now() // the tap's time, on the run's clock, before any hop
            scope.launch { session.send(action, tappedAt) }
        }
    }

    // One camera for the whole run (survives phase switches); the map and the re-center button share it.
    val camera = remember { MapCamera() }
    CompositionLocalProvider(LocalMapCamera provides camera) {
        Box(Modifier.fillMaxSize().background(Theme.bg)) {
            when (val phase = snapshot.phase) {
                RunPhase.Gate -> GateScreen(snapshot, route, map, start = { send(UserAction.Start) }, close = onClose, sensor = gateSensor, connectSensor = connectSensor, pairSensor = pairSensor)
                is RunPhase.Countdown -> CountdownScreen(phase.endsAt, snapshot.type, session.wallClock, cancel = onClose)
                RunPhase.Active, is RunPhase.Paused -> LiveRunScreen(snapshot, route, session.wallClock, send, map)
                is RunPhase.Cooldown -> StoppedScreen(snapshot, skip = { send(UserAction.SkipCooldown) })
                RunPhase.Done -> LaunchedEffect(Unit) { onClose() } // RunFlow.closesAt: the root opens the Summary
            }
        }
    }
}

/** The flow's own rules, plain Kotlin so the script test can pin them. */
object RunFlow {
    /** summary-screen/01: the run is in `computing` once the snapshot is Done; the flow closes itself and the root opens the Summary. */
    fun closesAt(snapshot: RunSnapshot): Boolean = snapshot.phase == RunPhase.Done
}

/** The bare screens' shared frame: centred, a title block in the middle, the action at the bottom. */
@Composable
private fun BareScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dimens.gutter).padding(bottom = Dimens.screenBottom),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** `RunView.swift:70`. */
@Composable
fun StoppedScreen(snapshot: RunSnapshot, skip: () -> Unit) = BareScreen {
    Spacer(Modifier.weight(1f))
    Text("Run stopped", style = Type.screenTitle, color = Theme.text)
    Text("${RunFormat.time(snapshot.totalTime)} · ${RunFormat.distance(snapshot.distance)} km", style = Type.number22, color = Theme.text2)
    Text("Cool-down: measuring heart-rate recovery", style = Type.secondaryText, color = Theme.text3)
    Spacer(Modifier.weight(1f))
    BigButton("Skip", skip)
}
