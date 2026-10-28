package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.SensorId
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.sensor.PairedSensorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Settings artboard (`Settings.dc.html`) as a table over [SettingsModel.from] (profile-settings/02). */
class SettingsModelTest {
    private val profile = Profile(birthDate = Instant.parse("1996-03-14T00:00:00Z"), sex = Sex.Male, weightKg = 78.0, heightCm = 182.0, hrMax = 187, hrRest = 60)
    /** Wed 1 Oct 2026, 09:00 in UTC+2. */
    private val now = Instant.parse("2026-10-01T07:00:00Z")
    private val verity = PairedSensorInfo(SensorId("A1B2C3D4-0000"), "Polar Sense A1B2C3D4")

    private fun model(
        profile: Profile = this.profile, sensor: PairedSensorInfo? = verity, battery: Int? = 82, firmware: String? = "2.2.1",
        export: ExportState = ExportState.Idle, connected: Boolean = true,
    ) = SettingsModel.from(profile, now, 7200, sensor, connected, battery, firmware, export, version = "Version 1.0 (14)")

    @Test
    fun the_profile_and_heart_rate_rows_read_as_on_the_artboard() {
        val m = model()
        assertEquals("30 · 78 kg · 182 cm", m.profileValue)
        assertEquals("born 14.03.1996 · male", m.profileNote)
        assertEquals("max 187 · rest 60", m.heartRate)
    }

    @Test
    fun the_age_turns_on_the_birthday_not_before() {
        val dayBefore = profile.copy(birthDate = Instant.parse("1996-10-02T00:00:00Z"))
        val onTheDay = profile.copy(birthDate = Instant.parse("1996-10-01T00:00:00Z"))
        assertEquals("29 · 78 kg · 182 cm", model(dayBefore).profileValue)
        assertEquals("30 · 78 kg · 182 cm", model(onTheDay).profileValue)
    }

    @Test
    fun a_weight_with_a_decimal_keeps_it_and_a_woman_reads_female() {
        val m = model(profile.copy(weightKg = 61.3, heightCm = 168.5, sex = Sex.Female))
        assertEquals("30 · 61.3 kg · 168.5 cm", m.profileValue)
        assertTrue(m.profileNote.endsWith("· female"))
    }

    @Test
    fun the_paired_sensor_shows_its_name_id_firmware_and_battery() {
        val s = model().sensor!!
        assertEquals("Polar Verity Sense", s.name)
        assertEquals("ID A1B2C3D4 · firmware 2.2.1", s.detail)
        assertEquals("82 %", s.battery)
        assertEquals("ID A1B2C3D4", model(firmware = null, battery = null).sensor!!.detail)
        assertNull(model(battery = null).sensor!!.battery)
    }

    @Test
    fun no_paired_sensor_has_no_sensor_block() = assertNull(model(sensor = null).sensor)

    @Test
    fun export_reads_idle_busy_saved_and_failed() {
        assertEquals(SettingsModel.Export("Export backup", enabled = true, line = null), model().export)
        assertEquals(SettingsModel.Export("Exporting…", enabled = false, line = null), model(export = ExportState.Busy).export)
        assertEquals("Saved northform-backup-20261001-0900.json to Files", model(export = ExportState.Saved("northform-backup-20261001-0900.json")).export.line)
        val failed = model(export = ExportState.Failed("disk full")).export
        assertEquals("Couldn't export: disk full", failed.line)
        assertTrue(failed.enabled, "a failed export can be tried again")
    }

    @Test
    fun units_backup_copy_and_version_are_fixed() {
        val m = model()
        assertEquals("Metric (km, kg, 24 h)", m.units)
        assertEquals("All runs and raw streams, saved to Files.", m.backupDetail)
        assertEquals("Version 1.0 (14)", m.version)
        assertFalse(m.backupDetail.contains("profile"), "backup v1 carries no current profile (D26)")
    }

    @Test
    fun a_failed_profile_save_shows_under_the_profile() {
        assertEquals("Couldn't save your profile: disk full", SettingsModel.from(profile, now, 7200, null, false, null, null, ExportState.Idle, "v", profileSaveError = "disk full").profileError)
        assertNull(model().profileError)
    }

    /** The sensor card says whether the paired sensor is connected now, as Today's card does. */
    @Test
    fun the_sensor_card_says_whether_it_is_connected() {
        assertEquals(SettingsModel.Status("Connected", ok = true), model().sensor!!.status)
        assertEquals(SettingsModel.Status("Not connected", ok = false), model(connected = false, battery = null).sensor!!.status)
    }
}
