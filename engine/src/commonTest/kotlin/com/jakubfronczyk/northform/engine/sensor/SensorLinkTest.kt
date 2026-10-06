package com.jakubfronczyk.northform.engine.sensor

import com.jakubfronczyk.northform.core.DisconnectReason
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.SensorState
import com.jakubfronczyk.northform.core.gatt.Gatt
import com.jakubfronczyk.northform.core.ports.BleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Scripted inputs → expected commands. Pure, runs on the JVM; this is where the reconnect policy is proven. */
class SensorLinkTest {
    private val t0 = Instant.fromEpochSeconds(1_800_000_000)
    private val sensor = SensorId("3F2504E0-4F89-11D3-9A0C-0305E82C3301")
    private val ready = BleEvent.Ready(sensor, listOf(
        BleEvent.Characteristic(Gatt.heartRateService, Gatt.heartRateMeasurement, notify = true, read = false),
        BleEvent.Characteristic(Gatt.batteryService, Gatt.batteryLevel, notify = false, read = true),
    ))

    private fun run(vararg inputs: SensorLink.Input): Pair<SensorLink.State, List<SensorLink.Command>> {
        var s = SensorLink.State()
        val all = mutableListOf<SensorLink.Command>()
        for (i in inputs) { val r = SensorLink.reduce(s, i); s = r.state; all += r.commands }
        return s to all
    }

    @Test
    fun connect_reports_connecting_and_issues_connect() {
        val (s, c) = run(SensorLink.Input.Connect(sensor, t0))
        assertEquals(sensor, s.wanted)
        assertEquals(listOf(SensorLink.Command.Report(SensorState.Connecting, t0), SensorLink.Command.Connect(sensor)), c)
    }

    @Test
    fun connected_then_ready_discovers_reads_battery_and_firmware_and_reports_connected() {
        val (s, c) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0 + 1.seconds),
            SensorLink.Input.Ble(ready, t0 + 2.seconds),
        )
        assertTrue(s.ready)
        assertTrue(SensorLink.Command.Discover(sensor, Gatt.servicesUsed) in c)
        assertTrue(SensorLink.Command.Read(sensor, Gatt.batteryLevel) in c)
        assertTrue(SensorLink.Command.Read(sensor, Gatt.firmwareRevision) in c)
        assertTrue(SensorLink.Command.Report(SensorState.Connected(null), t0 + 2.seconds) in c)
        // No listener yet → HR notify stays off (restoration rule D9 step 4 relies on this too).
        assertTrue(c.none { it is SensorLink.Command.SetNotify && it.characteristic == Gatt.heartRateMeasurement })
    }

    @Test
    fun hr_notify_follows_listener_count() {
        val (_, on) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(ready, t0),
            SensorLink.Input.Listeners(1, t0),
        )
        assertTrue(SensorLink.Command.SetNotify(sensor, Gatt.heartRateMeasurement, true) in on)
        val (_, off) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(ready, t0),
            SensorLink.Input.Listeners(1, t0),
            SensorLink.Input.Listeners(0, t0),
        )
        assertTrue(SensorLink.Command.SetNotify(sensor, Gatt.heartRateMeasurement, false) in off)
    }

    @Test
    fun hr_value_emits_sample_and_drops_zero() {
        val base = arrayOf(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(ready, t0),
        )
        val (_, c) = run(*base, SensorLink.Input.Ble(BleEvent.Value(sensor, Gatt.heartRateMeasurement, byteArrayOf(0x00, 72)), t0 + 3.seconds))
        assertTrue(c.any { it is SensorLink.Command.Emit && it.sample.bpm == 72 && it.sample.t == t0 + 3.seconds })
        val (_, zero) = run(*base, SensorLink.Input.Ble(BleEvent.Value(sensor, Gatt.heartRateMeasurement, byteArrayOf(0x00, 0)), t0))
        assertTrue(zero.none { it is SensorLink.Command.Emit })
    }

    @Test
    fun drop_with_system_reconnect_reports_lost_then_connecting_and_does_not_reconnect_itself() {
        val (s, c) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(BleEvent.Disconnected(sensor, isReconnecting = true, message = null), t0 + 60.seconds),
        )
        val reports = c.filterIsInstance<SensorLink.Command.Report>().map { it.state }
        assertEquals(listOf(SensorState.Connecting, SensorState.Disconnected(DisconnectReason.Lost), SensorState.Connecting), reports)
        assertEquals(1, c.count { it is SensorLink.Command.Connect }) // only the original connect
        assertEquals(null, s.connected)
    }

    @Test
    fun final_disconnect_reissues_a_pending_connect() {
        val (_, c) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(BleEvent.Disconnected(sensor, isReconnecting = false, message = null), t0 + 60.seconds),
        )
        assertEquals(2, c.count { it is SensorLink.Command.Connect })
    }

    @Test
    fun failed_connect_backs_off_2_4_8_16_30_seconds() {
        var s = SensorLink.State()
        var t = t0
        var cmds: List<SensorLink.Command>
        SensorLink.reduce(s, SensorLink.Input.Connect(sensor, t)).also { s = it.state }
        val gaps = mutableListOf<Long>()
        repeat(5) {
            SensorLink.reduce(s, SensorLink.Input.Ble(BleEvent.FailedToConnect(sensor, null), t)).also { s = it.state }
            val failedAt = t
            do {
                t += 1.seconds
                val r = SensorLink.reduce(s, SensorLink.Input.Tick(t)); s = r.state; cmds = r.commands
            } while (cmds.none { it is SensorLink.Command.Connect })
            gaps += (t - failedAt).inWholeSeconds
        }
        assertEquals(listOf(2L, 4L, 8L, 16L, 30L), gaps)
    }

    @Test
    fun connect_timeout_after_15s_surfaces_but_link_keeps_wanting() {
        val (s, c) = run(SensorLink.Input.Connect(sensor, t0), SensorLink.Input.Tick(t0 + 15.seconds))
        assertTrue(SensorLink.Command.ConnectTimedOut(sensor) in c)
        assertEquals(sensor, s.wanted)
    }

    @Test
    fun bluetooth_off_is_not_a_drop_and_power_on_reconnects() {
        val (_, c) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(BleEvent.PowerOff, t0 + 10.seconds),
            SensorLink.Input.Ble(BleEvent.PowerOn, t0 + 20.seconds),
        )
        val reports = c.filterIsInstance<SensorLink.Command.Report>().map { it.state }
        assertTrue(SensorState.BluetoothOff in reports)
        assertTrue(reports.none { it == SensorState.Disconnected(DisconnectReason.Lost) })
        assertEquals(2, c.count { it is SensorLink.Command.Connect })
    }

    @Test
    fun restored_peripheral_is_adopted_without_turning_hr_on() {
        val (s, c) = run(
            SensorLink.Input.Ble(BleEvent.Restored(listOf(BleEvent.RestoredPeripheral(sensor, "Polar Sense ABC", connected = true))), t0),
            SensorLink.Input.Ble(ready, t0 + 1.seconds),
        )
        assertEquals(sensor, s.wanted)
        assertEquals(sensor, s.connected)
        assertTrue(SensorLink.Command.Discover(sensor, Gatt.servicesUsed) in c)
        assertTrue(c.none { it is SensorLink.Command.SetNotify && it.characteristic == Gatt.heartRateMeasurement })
    }

    @Test
    fun battery_without_notify_is_polled_every_5_minutes() {
        val (_, c) = run(
            SensorLink.Input.Connect(sensor, t0),
            SensorLink.Input.Ble(BleEvent.Connected(sensor), t0),
            SensorLink.Input.Ble(ready, t0),
            SensorLink.Input.Tick(t0 + 299.seconds),
            SensorLink.Input.Tick(t0 + 300.seconds),
        )
        assertEquals(2, c.count { it == SensorLink.Command.Read(sensor, Gatt.batteryLevel) }) // on ready + at 300 s
    }
}
