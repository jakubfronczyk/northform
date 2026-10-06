package com.jakubfronczyk.northform.ui.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.ports.HeartRateSensor

/**
 * Spike step 2 screen (throwaway): scan → tap a Polar Sense → HR on screen. It only renders flows from
 * the `HeartRateSensor` port; no Bluetooth type appears here (dependency rule).
 */
@Composable
fun SpikeScreen(sensor: HeartRateSensor, bootLog: List<String>) {
    val scope = rememberCoroutineScope()
    val state by sensor.states().collectAsState(initial = null)
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<DiscoveredSensor>()) }
    var lastHr by remember { mutableStateOf<HrSample?>(null) }
    var samples by remember { mutableStateOf(0) }

    // Collecting heartRate() is what turns HR notifications ON (listener count → SensorLink).
    LaunchedEffect(Unit) {
        sensor.heartRate().collect { lastHr = it; samples++ }
    }
    LaunchedEffect(scanning) {
        if (scanning) sensor.search().scan(emptyList<DiscoveredSensor>()) { acc, d -> if (acc.any { it.id == d.id }) acc else acc + d }
            .collect { found = it }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Northform spike", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("link: ${state?.state?.label() ?: "—"}")
        Text("HR: ${lastHr?.bpm ?: "—"} bpm   samples: $samples", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { scanning = !scanning }) { Text(if (scanning) "Stop scan" else "Scan for Polar Sense") }
        OutlinedButton(onClick = { scope.launch { sensor.disconnect() } }) { Text("Disconnect") }
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(found, key = { it.id.value }) { d ->
                OutlinedButton(onClick = { scanning = false; scope.launch { runCatching { sensor.connect(d.id) } } }) {
                    Text("${d.name ?: "?"}  ${d.rssi} dBm")
                }
            }
            items(bootLog) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun SensorState.label() = when (this) {
    is SensorState.Disconnected -> "disconnected (${reason.name.lowercase()})"
    SensorState.Connecting -> "connecting"
    is SensorState.Connected -> "connected, battery ${battery ?: "?"} %"
    SensorState.BluetoothOff -> "bluetooth off"
    SensorState.Unauthorized -> "unauthorized"
}
