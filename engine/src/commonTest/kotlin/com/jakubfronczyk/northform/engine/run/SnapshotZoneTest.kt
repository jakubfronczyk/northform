package com.jakubfronczyk.northform.engine.run

import com.jakubfronczyk.northform.core.HrSample
import com.jakubfronczyk.northform.core.metrics.zone
import com.jakubfronczyk.northform.testing.RunHarness
import com.jakubfronczyk.northform.testing.activeRun
import com.jakubfronczyk.northform.testing.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D23: the snapshot's zone is the `Zone` value of the run's profile, not a bare number. */
class SnapshotZoneTest {
    @Test
    fun snapshot_zone_is_the_profile_zone_value_of_the_live_heart_rate() {
        val run = activeRun()
        run.send(RunInput.Hr(HrSample(at(6), 150))) // reserve 127: 148.9 bpm starts Z3
        val zone = run.state.snapshot.zone!!
        assertEquals(RunHarness.config.profile.zones.zone(3), zone)
        assertEquals(3, zone.number)
        assertEquals("Z3", zone.label)
        assertEquals(0.7 to 0.8, zone.lowerFraction to zone.upperFraction, "the %HRR range travels with the zone")
    }

    @Test
    fun snapshot_zone_is_null_while_heart_rate_is_lost() {
        val run = activeRun()
        run.send(RunInput.Hr(HrSample(at(6), 150)))
        for (s in 7..12) run.send(RunInput.Tick(at(s))) // 5 s without a sample: HR lost
        assertNull(run.state.snapshot.hrNow)
        assertNull(run.state.snapshot.zone)
    }
}
