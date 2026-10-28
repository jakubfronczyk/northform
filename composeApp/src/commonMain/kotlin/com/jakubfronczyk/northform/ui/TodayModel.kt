package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.RecordingId
import com.jakubfronczyk.northform.core.RecordingType
import com.jakubfronczyk.northform.core.ports.SavedRecording
import com.jakubfronczyk.northform.ui.run.GateSensor
import com.jakubfronczyk.northform.ui.run.SummaryModel
import kotlin.time.Instant

/**
 * Everything the Today artboard shows, as plain values (today-screen spec): one pure function of `latest(3)` and the
 * sensor link, so the screen only lays out and the rules are table-tested without Compose ([SummaryModel]'s pattern).
 * Numbers are formatted as the Summary formats them.
 */
data class TodayModel(
    /** "Wed 1 Oct": today in the phone's zone. */
    val date: String,
    val sensor: Sensor,
    /** The newest saved recording; null when nothing is saved. */
    val last: Card?,
    /** The two before [last], never repeating it. */
    val recent: List<RecordingRow>,
    /** "No recordings yet" when nothing is saved, else null. */
    val emptyLine: String?,
) {
    /** "See all history ›": the way into History whenever anything is saved, Recent or not. */
    val showsHistory: Boolean get() = last != null

    /** The sensor card: the gate's link state (E1); [connected] picks green or amber. */
    data class Sensor(val name: String, val connected: Boolean, val status: String)

    /** "Last run · Tue 30 Sep · Tempo", "10.21" km, "52:40 · 5:09 /km · 156 bpm". */
    data class Card(val id: RecordingId, val type: RecordingType, val label: String, val distance: String, val detail: String)

    companion object {
        /** How many saved recordings Today reads: the card and two Recent rows. */
        const val SHOWN = 3

        /**
         * @param saved the saved list, newest first (every one: History shares it); the first [SHOWN] are used.
         * @param sensor the gate's link state; null = unknown (replay), shown as not connected.
         * @param sensorName the paired sensor's advertised name, if any.
         * @param tzOffset the phone's offset now, seconds east of UTC (the date line); each recording uses its own.
         */
        fun from(saved: List<SavedRecording>, sensor: GateSensor?, sensorName: String?, now: Instant, tzOffset: Int): TodayModel {
            val newest = saved.firstOrNull()
            return TodayModel(
                date = RunFormat.date(now, tzOffset),
                sensor = sensor(sensor, sensorName),
                last = newest?.let(::card),
                recent = saved.drop(1).take(SHOWN - 1).map { RecordingRow.of(it, now, tzOffset) },
                emptyLine = if (newest == null) "No recordings yet" else null,
            )
        }

        private fun sensor(link: GateSensor?, name: String?): Sensor {
            val connected = link?.connected == true
            val status = when {
                !connected -> "Not connected · tap to connect"
                link.batteryPercent != null -> "Connected · battery ${link.batteryPercent} %"
                else -> "Connected"
            }
            return Sensor(name ?: "Heart rate sensor", connected, status)
        }

        private fun card(s: SavedRecording): Card {
            val r = s.recording
            val label = listOfNotNull("Last ${SummaryModel.noun(r.type)}", RunFormat.date(r.startUtc, r.tzOffset), r.tag?.name).joinToString(" · ")
            val h = s.headline
            return Card(r.id, r.type, label, RunFormat.distance(h.distance), listOfNotNull(RunFormat.time(h.totalTime), "${RunFormat.pace(h.avgPace)} /km", RunFormat.bpm(h.avgHr)).joinToString(" · "))
        }
    }
}
