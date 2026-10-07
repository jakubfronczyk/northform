package com.jakubfronczyk.northform.ui.sensor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.ui.Theme
import com.jakubfronczyk.northform.ui.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The sensor screen (`Features/SensorView.swift`): find, connect, see live state and heart rate. Reads
 * only the `HeartRateSensor` port; the sensor's own state machine decides everything (reconnects, drops,
 * Bluetooth off). `onConnected` lets the app remember the sensor so it reconnects at launch.
 */
@Composable
fun SensorScreen(sensor: HeartRateSensor, onConnected: (SensorId) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by sensor.states().collectAsState(initial = null)
    var bpm by remember { mutableStateOf<Int?>(null) }
    var found by remember { mutableStateOf(listOf<DiscoveredSensor>()) }
    var error by remember { mutableStateOf<String?>(null) }

    // `SensorViewModel.follow()`: heart rate (ends only on Unauthorized) and the nearby list, while the screen is up.
    LaunchedEffect(sensor) {
        launch {
            try { sensor.heartRate().collect { bpm = it.bpm } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Heart rate unavailable: ${e.message}" }
        }
        launch {
            try { sensor.search().collect { d -> found = found.filter { it.id != d.id } + d } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Search failed: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sensor", style = Type.screenTitle, color = Theme.text)
            Spacer(Modifier.weight(1f))
            Text("Close", color = Theme.text2, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        val s = state?.state
        val color = if (s is SensorState.Connected) Theme.ok else Theme.warn
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(s?.text() ?: "Not connected", style = Type.statusLabel.copy(fontSize = Type.secondaryText.fontSize, fontWeight = FontWeight.SemiBold), color = color)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bpm?.toString() ?: "–", style = Type.number54Black, color = Theme.text)
            Spacer(Modifier.width(4.dp))
            Text("bpm", style = Type.unitLabel, color = Theme.text2, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Nearby", style = Type.rowLabel, color = Theme.text2)
        for (d in found) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                    error = null
                    scope.launch {
                        try { sensor.connect(d.id); onConnected(d.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Couldn't connect: ${e.message}" }
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.name ?: d.id.value.take(8), color = Theme.text)
                Spacer(Modifier.weight(1f))
                Text("${d.rssi} dBm", style = Type.tertiaryText, color = Theme.text3)
            }
        }
        error?.let { Text(it, style = Type.rowLabel, color = Theme.warn) }
    }
}

/** `SensorViewModel.stateText`. */
private fun SensorState.text() = when (this) {
    is SensorState.Connected -> battery?.let { "Connected · $it %" } ?: "Connected"
    SensorState.Connecting -> "Connecting…"
    is SensorState.Disconnected -> if (reason == DisconnectReason.Lost) "Sensor lost · reconnecting…" else "Not connected"
    SensorState.BluetoothOff -> "Bluetooth is off"
    SensorState.Unauthorized -> "Bluetooth not allowed · Settings → Northform"
}
