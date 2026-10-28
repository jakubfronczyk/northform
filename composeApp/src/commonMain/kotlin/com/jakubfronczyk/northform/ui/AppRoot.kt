package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.ui.run.GateSensor
import com.jakubfronczyk.northform.ui.run.RunFlow

/**
 * Navigation (D77 restated, `App/Sources/NorthformApp.swift`): Today is the root; the run flow is a
 * full-screen cover without swipe dismiss (a `when` on `activeRun`); the Sensor screen is a sheet with
 * Close. The composition root supplies the session, the map and the sensor screen; nothing here decides
 * anything about a run.
 */
@Composable
fun AppRoot(
    activeRun: RunSessionHandle?,
    /** What Today shows (today-screen spec); the root builds it from the saved list and the sensor link. */
    today: TodayModel,
    /** The Run or Walk tile: starts a recording of that type. */
    onStartRun: (RecordingType) -> Unit,
    onCloseRun: () -> Unit,
    map: @Composable (route: Route) -> Unit,
    sensorScreen: @Composable (close: () -> Unit) -> Unit,
    /** E1: the gate's sensor row, from the real link; null = unknown (replay has no link to ask). Required so the root can't forget it. */
    gateSensor: GateSensor?,
    /** The gate's "Connect" for a sensor that is not connected. */
    connectSensor: () -> Unit,
    /** The R28 crash prompt while a recording waits for the user's choice: a sheet over Today that cannot be dismissed (Swift D77 restated). */
    crashPrompt: (@Composable () -> Unit)? = null,
    /** R27: the Summary of the run that just finished; shown over Today while non-null. */
    summary: (@Composable () -> Unit)? = null,
) {
    var showSensor by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        when {
            // The Sensor sheet first: the gate's "Pair a sensor" opens it over a run still at the gate, and Close returns there.
            showSensor -> sensorScreen { showSensor = false }
            activeRun != null -> RunFlow(
                activeRun, onClose = onCloseRun, map = map, gateSensor = gateSensor, connectSensor = connectSensor,
                pairSensor = { showSensor = true },
            )
            summary != null -> summary()
            else -> {
                TodayScreen(today, start = onStartRun, openSensor = { showSensor = true })
                crashPrompt?.invoke()
            }
        }
    }
}
