package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RunSessionHandle
import com.jakubfronczyk.northform.ui.run.PrimaryButton
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
    onStartRun: () -> Unit,
    onCloseRun: () -> Unit,
    map: @Composable (route: Route) -> Unit,
    sensorScreen: @Composable (close: () -> Unit) -> Unit,
) {
    var showSensor by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        when {
            activeRun != null -> RunFlow(activeRun, onClose = onCloseRun, map = map)
            showSensor -> sensorScreen { showSensor = false }
            else -> TodayScreen(startRun = onStartRun, openSensor = { showSensor = true })
        }
    }
}

/** `Features/TodayView.swift`. */
@Composable
fun TodayScreen(startRun: () -> Unit, openSensor: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 50.dp)) {
        Text("Today", style = Type.homeTitle, color = Theme.text)
        Spacer(Modifier.height(8.dp))
        Text("No runs yet", style = Type.secondaryText, color = Theme.text2)
        Text("Sensor", color = Theme.text2, modifier = Modifier.clickable(onClick = openSensor).padding(vertical = 12.dp))
        Spacer(Modifier.weight(1f))
        PrimaryButton("Start run", startRun)
    }
}
