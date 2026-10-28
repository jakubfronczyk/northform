package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.ProfileForm.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/** The profile editor's rules as a table over [ProfileForm.check] (profile-settings/03). */
class ProfileFormTest {
    /** Wed 1 Oct 2026, 09:00 in UTC+2. */
    private val now = Instant.parse("2026-10-01T07:00:00Z")
    private val zones = HRZones(listOf(0.52, 0.62, 0.72, 0.82, 0.92))
    private val valid = ProfileForm(birthDate = "14.03.1996", sex = Sex.Male, weight = "78", height = "182", hrMax = "187", hrRest = "60")

    private fun check(form: ProfileForm) = form.check(now, 7200, zones)
    private fun error(form: ProfileForm, field: Field) = check(form).errors[field]

    @Test
    fun a_valid_form_is_a_profile_keeping_the_zones_and_shows_the_age() {
        val c = check(valid)
        assertEquals(Profile(Instant.parse("1996-03-14T00:00:00Z"), Sex.Male, 78.0, 182.0, hrMax = 187, hrRest = 60, zones = zones), c.profile)
        assertEquals(emptyMap(), c.errors)
        assertEquals("Age 30", c.ageHint)
    }

    @Test
    fun a_profile_fills_the_form_and_reads_back_unchanged() {
        val p = Profile(Instant.parse("1989-07-04T00:00:00Z"), Sex.Female, 61.3, 168.5, hrMax = 194, hrRest = 47, zones = zones)
        val form = ProfileForm.of(p)
        assertEquals(ProfileForm("04.07.1989", Sex.Female, "61.3", "168.5", "194", "47"), form)
        assertEquals(p, check(form).profile)
    }

    @Test
    fun the_birth_date_must_be_a_real_day_in_dd_mm_yyyy() {
        for (bad in listOf("", "1996-03-14", "14.3.1996", "31.02.1996", "00.03.1996", "14.13.1996")) {
            assertEquals("Use DD.MM.YYYY", error(valid.copy(birthDate = bad), Field.BirthDate), bad)
        }
        assertNull(error(valid.copy(birthDate = "29.02.1996"), Field.BirthDate), "1996 was a leap year")
        assertEquals("Use DD.MM.YYYY", error(valid.copy(birthDate = "29.02.1997"), Field.BirthDate), "1997 was not")
        for (sep in listOf("/", "-", ",")) assertNull(error(valid.copy(birthDate = "14${sep}03${sep}1996"), Field.BirthDate), "keyboards without a dot: $sep")
        assertNull(check(valid.copy(birthDate = "")).ageHint)
    }

    @Test
    fun the_age_is_13_to_100() {
        assertNull(error(valid.copy(birthDate = "01.10.2013"), Field.BirthDate), "13 today")
        assertEquals("Age must be 13–100", error(valid.copy(birthDate = "02.10.2013"), Field.BirthDate), "13 tomorrow")
        assertNull(error(valid.copy(birthDate = "02.10.1925"), Field.BirthDate), "100")
        assertEquals("Age must be 13–100", error(valid.copy(birthDate = "01.10.1925"), Field.BirthDate), "101 today")
    }

    @Test
    fun weight_height_and_heart_rates_have_their_ranges() {
        val cases = listOf(
            Triple(Field.Weight, listOf("30", "250", "61,3"), listOf("29.9", "250.1", "", "abc")),
            Triple(Field.Height, listOf("100", "250"), listOf("99", "251", "")),
            Triple(Field.HrMax, listOf("100", "230", ""), listOf("99", "231", "187.5")), // blank: estimated (first-launch-setup)
            Triple(Field.HrRest, listOf("30", "80", ""), listOf("29", "121")), // blank: 60
        )
        for ((field, ok, bad) in cases) {
            fun with(v: String) = when (field) {
                Field.Weight -> valid.copy(weight = v); Field.Height -> valid.copy(height = v)
                Field.HrMax -> valid.copy(hrMax = v); Field.HrRest -> valid.copy(hrRest = v); else -> valid
            }
            for (v in ok) assertNull(error(with(v), field), "$field $v")
            for (v in bad) assertNotNull(error(with(v), field), "$field $v")
        }
        assertEquals("30–250 kg", error(valid.copy(weight = "20"), Field.Weight))
        assertEquals("100–250 cm", error(valid.copy(height = "20"), Field.Height))
        assertEquals("100–230 bpm", error(valid.copy(hrMax = "20"), Field.HrMax))
        assertEquals("30–120 bpm", error(valid.copy(hrRest = "20"), Field.HrRest))
        assertEquals(61.3, check(valid.copy(weight = "61,3")).profile?.weightKg, "a comma reads as the decimal point")
    }

    @Test
    fun max_must_be_at_least_20_above_rest() {
        assertNull(error(valid.copy(hrMax = "120", hrRest = "100"), Field.HrMax))
        assertEquals("At least 20 above resting", error(valid.copy(hrMax = "119", hrRest = "100"), Field.HrMax))
        assertNull(check(valid.copy(hrMax = "119", hrRest = "100")).profile, "Save stays off")
    }

    // ── first-launch-setup/01 ──

    @Test
    fun a_blank_max_is_estimated_from_the_age_and_the_hint_says_so() {
        val c = check(valid.copy(hrMax = ""))
        assertEquals(187, c.profile?.hrMax, "208 − 0.7 × 30")
        assertEquals("Not sure? We estimate 187 from your age and update it when you go higher.", c.maxHint)
        assertEquals("Not sure? We estimate — from your age and update it when you go higher.", check(valid.copy(hrMax = "", birthDate = "")).maxHint)
    }

    @Test
    fun a_blank_rest_is_60() {
        assertEquals(60, check(valid.copy(hrRest = "  ")).profile?.hrRest)
    }

    @Test
    fun the_estimate_still_has_to_clear_rest_by_20() {
        assertEquals("At least 20 above resting", error(valid.copy(birthDate = "01.10.1926", hrMax = "", hrRest = "120"), Field.HrMax), "estimate 138 − 120 < 20")
    }
}
