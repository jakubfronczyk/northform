package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.metrics.Zone
import kotlin.math.roundToInt

/** first-launch-setup: the four steps in D71's order, each with its "n of 4". */
enum class SetupStep {
    AboutYou, Permissions, Pair, Zones;

    val progress: String get() = "${ordinal + 1} of ${entries.size}"

    /** The step after this one; null after the last (Today). */
    fun next(): SetupStep? = entries.getOrNull(ordinal + 1)

    /** The step before; null on the first (it has no Back). */
    fun back(): SetupStep? = entries.getOrNull(ordinal - 1)
}

/**
 * Step 4, "Your zones" (`SetupZones.dc.html`): Z0–Z5 with their % of heart-rate reserve and bpm from the saved profile.
 * Bpm by [Profile.bpmAt], the rule the zone editor uses too, so the two screens agree.
 */
data class SetupZones(val subtitle: String, val rows: List<Row>) {
    /** "Z3 Moderate", "70–80 %", "149–162". */
    data class Row(val label: String, val percent: String, val bpm: String)

    companion object {
        fun of(profile: Profile): SetupZones {
            val bounds = profile.zones.lowerBounds.map { (it * 100).roundToInt() }
            fun bpm(p: Int) = profile.bpmAt(p / 100.0)
            val edges = bounds + 100
            val rows = listOf(Row("Z0 ${Zone.names[0]}", "below ${bounds.first()} %", "below ${bpm(bounds.first())}")) +
                bounds.indices.map { i ->
                    Row("Z${i + 1} ${Zone.names[i + 1]}", "${edges[i]}–${edges[i + 1]} %", "${bpm(edges[i])}–${bpm(edges[i + 1])}")
                }
            return SetupZones("From max ${profile.hrMax} and resting ${profile.hrRest} bpm, as % of heart rate reserve.", rows)
        }
    }
}

/**
 * Step 2, "Two permissions" (`SetupPermissions.dc.html`): what iOS says for each, and what the runner can do about it.
 * Bluetooth was already asked when the app opened (the central is created at boot, D9), so its row only reports.
 */
data class SetupPermissions(val bluetooth: Row, val location: Row, val locationNote: String?) {
    /** Location as iOS reports it, mapped by the composition root from the platform's authorization. */
    enum class Location { NotAsked, Allowed, Denied, Restricted }

    /** What the row's button does. */
    enum class Action { Allow, OpenSettings }

    /**
     * Whether iOS allows it, and the button beside it, if any. [showsNotAllowed]: the amber "Not allowed" line under the
     * reason, once iOS has said no (denied, restricted); never before the runner was asked, where Allow says it all.
     */
    data class Row(val allowed: Boolean, val action: Action?) {
        val showsNotAllowed: Boolean get() = !allowed && action != Action.Allow
    }

    companion object {
        fun of(bluetoothAllowed: Boolean, location: Location) = SetupPermissions(
            bluetooth = if (bluetoothAllowed) Row(true, null) else Row(false, Action.OpenSettings),
            location = when (location) {
                Location.Allowed -> Row(true, null)
                Location.NotAsked -> Row(false, Action.Allow)
                Location.Denied -> Row(false, Action.OpenSettings)
                Location.Restricted -> Row(false, null) // parental controls or a profile: nothing the runner can change
            },
            locationNote = if (location == Location.Denied) "Location is off for this app. Turn it on in iOS Settings, then come back." else null,
        )
    }
}
