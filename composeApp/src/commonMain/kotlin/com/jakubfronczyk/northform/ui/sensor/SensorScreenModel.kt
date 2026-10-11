package com.jakubfronczyk.northform.ui.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.DiscoveredSensor
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.ports.HeartRateSensor
import com.jakubfronczyk.northform.engine.sensor.SensorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Sensor screen's model (SU10, D72; `Features/SensorView.swift:59-132` grown to the design's
 * states). Pure Kotlin over the `HeartRateSensor` port and [SensorDetails]: no Compose, so the same
 * class drives the screen, the previews (on [FakeHeartRateSensor]) and the table tests.
 *
 * What it decides (and nothing else): which of the design's states to show, and the 30 s search
 * (`Sensor.dc.html`: "No signal from A1B2C3D4 in 30 s"). Pairing, reconnects and firmware judgement
 * are the link's (`SensorLink`, adapters/01); the model only reads them.
 *
 * Lifecycle: [follow] runs while the screen is up (the Swift `.task {}`), and is where the port's
 * flows are collected, so the scan and HR notify stop with the screen.
 */
class SensorScreenModel(
    private val sensor: HeartRateSensor,
    private val details: SensorDetails,
    private val searchTimeout: Duration = SEARCH_TIMEOUT,
) {
    /** The design's artboard variants, plus the two radio states the port can report. */
    enum class Mode { Connected, Disconnected, Searching, NotFound, BluetoothOff, Unauthorized }

    data class Ui(
        val mode: Mode = Mode.Disconnected,
        val paired: PairedSensorInfo? = null,
        val bpm: Int? = null,
        val battery: Int? = null,
        val firmware: String? = null,
        /** E16: firmware below 2.1.0 (the link's rule). */
        val firmwareTooOld: Boolean = false,
        /**
         * What `search()` yielded while looking for a sensor to pair: the paired one first, strangers after. The only
         * place signal is shown (D34): an advert's RSSI is current while scanning; once connected it would be a stale
         * last-seen value, and polling `readRSSI` costs radio time for nothing a runner acts on.
         */
        val nearby: List<DiscoveredSensor> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private var scanJob: Job? = null
    private var userSearching = false
    private var notFound = false
    private var state: SensorState = SensorState.Disconnected(DisconnectReason.User)

    /** Collects the port while the screen is up; returns only when cancelled. */
    suspend fun follow(): Unit = coroutineScope {
        scope = this
        state = sensor.states().first().state // the port starts with the current value; read it before deciding anything
        launch { details.paired.collect { render() } }
        launch { details.firmware.collect { render() } }
        launch {
            sensor.states().collect { change ->
                state = change.state
                if (state is SensorState.Connected) notFound = false
                render()
            }
        }
        launch {
            try { sensor.heartRate().collect { s -> _ui.update { it.copy(bpm = s.bpm) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("Heart rate unavailable: ${e.message}") }
        }
        // The screen opened onto a link that is already trying (launch reconnect, a drop): give it the same 30 s.
        // It is the search job like any other, so cancel() and connect() stop it (a stale watch would disconnect a fresh try).
        if (state is SensorState.Connecting || state == SensorState.Disconnected(DisconnectReason.Lost)) {
            val id = details.paired.value?.id
            searchJob = launch { watch(id) }
        }
        if (details.paired.value == null) findNearby()
    }

    /** SU10 "connect" / "try again": the paired sensor, with the 30 s search. */
    fun connect() {
        val id = details.paired.value?.id ?: return findNearby()
        connect(id)
    }

    /** Pair a nearby sensor (never automatic: only this tap connects a stranger). The link remembers it. */
    fun pair(id: SensorId) {
        scanJob?.cancel(); scanJob = null
        connect(id)
    }

    /** SU10 "cancel": stop looking; the link stops trying too. */
    fun cancel() {
        searchJob?.cancel(); searchJob = null
        scanJob?.cancel(); scanJob = null
        userSearching = false
        notFound = false
        scope?.launch { sensor.disconnect() }
        render()
    }

    fun disconnect() {
        notFound = false
        scope?.launch { sensor.disconnect() }
    }

    fun forget() {
        cancel()
        scope?.launch { details.forget() }
    }

    private fun connect(id: SensorId) {
        _ui.update { it.copy(error = null) }
        searchJob?.cancel()
        searchJob = scope?.launch {
            launch {
                // The port throws after the link's 15 s; the link keeps trying and the 30 s below decides.
                try { sensor.connect(id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            watch(id)
        }
    }

    /** Searching until connected, or `notFound` after [searchTimeout], at which point the link is told to stop. */
    private suspend fun watch(id: SensorId?) {
        userSearching = true
        notFound = false
        render()
        val connected = withTimeoutOrNull(searchTimeout) {
            sensor.states().first { it.state is SensorState.Connected }
        }
        userSearching = false
        if (connected == null) {
            notFound = true
            sensor.disconnect()
        }
        render()
    }

    /** No paired sensor: scan and list what comes back (Polar only, the link filters). 30 s with nothing ⇒ not found. */
    private fun findNearby() {
        scanJob?.cancel()
        userSearching = true
        notFound = false
        _ui.update { rendered(it.copy(nearby = emptyList())) }
        scanJob = scope?.launch {
            val found = withTimeoutOrNull(searchTimeout) {
                try {
                    sensor.search().collect { d ->
                        _ui.update { ui -> rendered(ui.copy(nearby = sorted(ui.nearby.filter { it.id != d.id } + d))) }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail("Search failed: ${e.message}") }
            }
            // The scan has stopped either way (timeout or the flow ended): stop showing Searching. With adverts in
            // hand the list stays up to pick from; with none, not found.
            userSearching = false
            notFound = _ui.value.nearby.isEmpty()
            render()
        }
    }

    private fun sorted(list: List<DiscoveredSensor>): List<DiscoveredSensor> {
        val paired = details.paired.value?.id
        return list.sortedWith(compareByDescending<DiscoveredSensor> { it.id == paired }.thenByDescending { it.rssi })
    }

    private fun fail(message: String) = _ui.update { it.copy(error = message) }

    private fun render() = _ui.update(::rendered)

    /** [ui] with every field the port and the link decide brought up to date (one `update`, one emission). */
    private fun rendered(ui: Ui): Ui {
        val s = state
        val paired = details.paired.value
        val firmware = details.firmware.value
        val mode = when {
            s is SensorState.Unauthorized -> Mode.Unauthorized
            s is SensorState.BluetoothOff -> Mode.BluetoothOff
            s is SensorState.Connected -> Mode.Connected
            userSearching -> Mode.Searching
            notFound -> Mode.NotFound
            // The link is reconnecting by itself (a drop, the launch reconnect): the design's searching look.
            s is SensorState.Connecting || s == SensorState.Disconnected(DisconnectReason.Lost) -> Mode.Searching
            else -> Mode.Disconnected
        }
        return ui.copy(
            mode = mode,
            paired = paired,
            bpm = if (mode == Mode.Connected) ui.bpm else null,
            battery = (s as? SensorState.Connected)?.battery,
            firmware = firmware,
            firmwareTooOld = firmware?.let(SensorLink::firmwareTooOld) ?: false,
        )
    }

    companion object {
        /** SU10: "try again (30 s search)". */
        val SEARCH_TIMEOUT: Duration = 30.seconds
    }
}
