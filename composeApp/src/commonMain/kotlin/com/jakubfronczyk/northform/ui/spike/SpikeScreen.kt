package com.jakubfronczyk.northform.ui.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.engine.sensor.GattHeartRateSensor
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * Spike step 2 screen (throwaway): scan → tap a Polar Sense → HR on screen. It renders three flows and
 * decides nothing: `states()` (the port), `heartRate()` (the port) and `diagnostics` (the link's live
 * view). No Bluetooth type appears here (dependency rule).
 */
@Composable
fun SpikeScreen(sensor: GattHeartRateSensor, bootLog: List<String>) {
    val scope = rememberCoroutineScope()
    val state by sensor.states().collectAsState(initial = null)
    val diag by sensor.diagnostics.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<DiscoveredSensor>()) }
    var lastHr by remember { mutableStateOf<HrSample?>(null) }
    var samples by remember { mutableStateOf(0) }
    var connectError by remember { mutableStateOf<String?>(null) }

    // Collecting heartRate() is what turns HR notifications ON (listener count → SensorLink).
    LaunchedEffect(Unit) {
        sensor.heartRate().collect { lastHr = it; samples++ }
    }
    LaunchedEffect(scanning) {
        if (scanning) {
            found = emptyList()
            sensor.search()
                .scan(emptyList<DiscoveredSensor>()) { acc, d -> if (acc.any { it.id == d.id }) acc else acc + d }
                .collect { found = it }
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Northform spike", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        // ── live radio / link state ──
        Text("central: ${diag.centralStateLabel}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "scan: " + when {
                diag.scanning -> "ON (filter HRS 0x180D)"
                diag.scanWanted -> "waiting for poweredOn"
                else -> "off"
            },
        )
        Text("link: ${state?.state?.label() ?: "—"}")
        Text(
            "wanted ${diag.wanted?.value?.take(8) ?: "—"} · connected ${diag.connected?.value?.take(8) ?: "—"} · " +
                "ready ${diag.ready} · hrNotify ${diag.hrNotifying} · listeners ${diag.listeners}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("battery ${diag.battery?.let { "$it %" } ?: "—"} · firmware ${diag.firmware ?: "—"}", style = MaterialTheme.typography.bodySmall)
        Text("radio events ${diag.events} · last: ${diag.lastEvent ?: "—"}", style = MaterialTheme.typography.bodySmall)
        connectError?.let { Text("connect: $it", color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(12.dp))
        Text("HR: ${lastHr?.bpm ?: "—"} bpm", style = MaterialTheme.typography.displayMedium)
        Text("samples: $samples", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Row {
            Button(onClick = { scanning = !scanning }) { Text(if (scanning) "Stop scan" else "Scan for Polar Sense") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { connectError = null; scope.launch { sensor.disconnect() } }) { Text("Disconnect") }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn {
            if (scanning && found.isEmpty()) item { Text("scanning… (sensor on, worn or button pressed)") }
            items(found, key = { it.id.value }) { d ->
                OutlinedButton(onClick = {
                    scanning = false
                    connectError = null
                    scope.launch { runCatching { sensor.connect(d.id) }.onFailure { connectError = it.message ?: it.toString() } }
                }) {
                    Text("${d.name ?: "?"}  ${d.rssi} dBm  ${d.id.value.take(8)}")
                }
            }
            item { Spacer(Modifier.height(16.dp)); Text("boot log", style = MaterialTheme.typography.labelLarge) }
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
