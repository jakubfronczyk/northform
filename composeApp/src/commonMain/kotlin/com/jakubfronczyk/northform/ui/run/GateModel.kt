package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.engine.run.RunReducer
import com.jakubfronczyk.northform.engine.run.RunSnapshot

/**
 * What the gate knows about the sensor beyond the snapshot (E1): whether one is connected, its
 * battery, and whether one is paired at all (with nothing paired there is nothing to connect: the gate
 * offers pairing instead). Not on `RunSessionHandle` today — the composition root passes it when it has
 * it; null means "unknown", which hides the battery row and assumes a sensor (the snapshot's `hrLost` still rules).
 */
data class GateSensor(val connected: Boolean, val batteryPercent: Int?, val paired: Boolean, /** The paired sensor's advertised name (visual-polish/03). */ val name: String? = null)

/**
 * The Gate artboard's copy and states as one pure function of the snapshot at `RunPhase.Gate`
 * (R24 readiness per engine/01: `hrLost == false` is HR ✓, `gpsLost == false` is GPS ✓, `hrNow` the
 * live bpm) and the optional [GateSensor] (E1, E13). Option A: the screen holds Start until both are
 * ✓; "Start anyway" sends the same `UserAction.Start`. Table-tested in `GateModelTest`.
 */
data class GateModel(
    /** The type chip: "Run" / "Walk" (today-screen/04: the tile that opened the gate). */
    val typeLabel: String,
    val title: String,
    val subtitle: String,
    val hr: HrRow,
    /** "Reading from Verity Sense" under a ready heart rate; "sensor" when the name is unknown (visual-polish/03). */
    val hrSource: String,
    val gpsReady: Boolean,
    val gpsTitle: String,
    val gpsSub: String,
    val battery: Battery?,
    /** The E1 line under the card, or null. */
    val warning: String?,
    val startLabel: String,
    val startEnabled: Boolean,
) {
    sealed interface HrRow {
        data class Ready(val bpm: String) : HrRow
        data object Waiting : HrRow
        /** Not connected. [action] is the button beside it: Connect a paired sensor, or Pair one when none is. */
        data class NoSensor(val action: SensorAction) : HrRow
    }

    enum class SensorAction(val label: String) { Connect("Connect"), Pair("Pair a sensor") }

    data class Battery(val percent: Int, val low: Boolean)

    /** "3 of 3 fixes" — the snapshot gives only ready/not, so the bars are all lit or none. */
    val fixesLit: Int get() = if (gpsReady) RunReducer.gateFixes else 0
    val fixLabel: String get() = "$fixesLit of ${RunReducer.gateFixes} fixes"
    /** Option A: "Start anyway" stands in whenever Start is held. */
    val showStartAnyway: Boolean get() = !startEnabled

    companion object {
        /** E1: warn below 20 %. */
        const val LOW_BATTERY_BELOW = 20

        fun from(snapshot: RunSnapshot, sensor: GateSensor? = null): GateModel {
            val noSensor = sensor?.connected == false
            val hrReady = !noSensor && !snapshot.hrLost
            val gpsReady = !snapshot.gpsLost
            val battery = sensor?.batteryPercent?.takeIf { !noSensor }?.let { Battery(it, low = it < LOW_BATTERY_BELOW) }
            val lowBattery = battery?.low == true
            val startOk = hrReady && gpsReady
            val noun = SummaryModel.noun(snapshot.type)
            return GateModel(
                typeLabel = SummaryModel.title(snapshot.type),
                title = when {
                    noSensor -> "No heart rate yet"
                    startOk -> "Ready to $noun"
                    else -> "Getting ready"
                },
                subtitle = when {
                    noSensor && !sensor.paired -> "Pair a sensor, or start a GPS only $noun."
                    noSensor -> "Connect the sensor, or start a GPS only $noun."
                    startOk && lowBattery -> "GPS is locked in. Sensor battery is low."
                    startOk -> "Heart rate and GPS are locked in"
                    !hrReady && !gpsReady -> "Waiting for heart rate and GPS."
                    !gpsReady -> "Waiting for GPS. Heart rate is ready."
                    else -> "Waiting for heart rate. GPS is ready."
                },
                hr = when {
                    noSensor -> HrRow.NoSensor(if (!sensor.paired) SensorAction.Pair else SensorAction.Connect)
                    hrReady -> HrRow.Ready(snapshot.hrNow?.toString() ?: NO_VALUE)
                    else -> HrRow.Waiting
                },
                hrSource = "Reading from ${sensor?.name ?: "sensor"}",
                gpsReady = gpsReady,
                gpsTitle = if (gpsReady) "GPS" else "GPS · finding signal…",
                gpsSub = if (gpsReady) "Locked in" else "Needs ${RunReducer.gateFixes} fixes within ${RunReducer.gateFixAccuracy.toInt()} m",
                battery = battery,
                warning = battery?.takeIf { it.low }?.let { "Sensor battery ${it.percent} % · may not last the $noun" },
                startLabel = when {
                    noSensor -> "Start · sensor not connected"
                    startOk -> "Start $noun"
                    !gpsReady -> "Start · waiting for GPS"
                    else -> "Start · waiting for heart rate"
                },
                startEnabled = startOk,
            )
        }
    }
}
