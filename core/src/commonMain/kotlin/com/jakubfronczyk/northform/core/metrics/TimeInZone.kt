package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.Profile
import kotlin.time.Duration

/**
 * H3 — time in zone Z0–Z5 over a recording's heart-rate stream. Pauses count (spec 04 "H1, H3, H5
 * cover total time including pauses"; D33 excludes pauses only from distance and pace). HR gaps
 * are skipped ([HrStream]), so the six values sum to the held HR time, not the total time.
 */
object TimeInZone {
    /** Every zone of the profile is a key, zero when never visited; iteration order Z0 → Z5. */
    fun compute(samples: List<HrSample>, profile: Profile, gap: Duration = HrStream.gap): Map<Zone, Duration> {
        val totals = profile.zones.all.associateWithTo(LinkedHashMap()) { Duration.ZERO }
        for (interval in HrStream.intervals(samples, gap)) {
            val zone = profile.zoneOf(interval.bpm)
            totals[zone] = totals.getValue(zone) + interval.duration
        }
        return totals
    }
}
