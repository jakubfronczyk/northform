package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.time.Instant

/**
 * The profile editor's fields as typed (profile-settings/03, `SetupProfile` in its edit context), checked by one pure
 * function so the rules are table-tested and the screen only shows them. `Profile`'s own checks stay the last guard.
 */
data class ProfileForm(
    /** DD.MM.YYYY. */
    val birthDate: String,
    val sex: Sex,
    /** kg; a comma reads as the decimal point. */
    val weight: String,
    /** cm. */
    val height: String,
    /** Blank: estimated from the age (first-launch-setup, D54). */
    val hrMax: String,
    /** Blank: 60. */
    val hrRest: String,
) {
    enum class Field { BirthDate, Weight, Height, HrMax, HrRest }

    /**
     * One message per invalid field; [profile] only when there are none (Save is enabled exactly then). [maxHint] is the
     * live estimate under the max field: "Not sure? We estimate 187 from your age…", "—" until the birth date is valid.
     */
    data class Checked(val errors: Map<Field, String>, val ageHint: String?, val maxHint: String, val profile: Profile?)

    /** @param now and [tzOffset] are the phone's (the age); [zones] are kept as they are (the zone editor owns them). */
    fun check(now: Instant, tzOffset: Int, zones: HRZones): Checked {
        val errors = LinkedHashMap<Field, String>()
        val born = RunFormat.parseBirthDate(birthDate)
        val age = born?.let { RunFormat.age(it, now, tzOffset) }
        when {
            born == null -> errors[Field.BirthDate] = "Use DD.MM.YYYY"
            age !in AGE -> errors[Field.BirthDate] = "Age must be 13–100"
        }
        // Each value, or null with its field's message recorded.
        fun <T> field(field: Field, message: String, value: T?): T? = value.also { if (it == null) errors[field] = message }
        val kg = field(Field.Weight, "30–250 kg", decimal(weight)?.takeIf { it in WEIGHT })
        val cm = field(Field.Height, "100–250 cm", decimal(height)?.takeIf { it in HEIGHT })
        val estimate = age?.takeIf { it in AGE }?.let(Profile::estimatedHrMax)
        val max = when {
            hrMax.isBlank() -> field(Field.HrMax, "Enter your birth date to estimate it", estimate)
            else -> field(Field.HrMax, "100–230 bpm", hrMax.trim().toIntOrNull()?.takeIf { it in HR_MAX })
        }
        val rest = if (hrRest.isBlank()) DEFAULT_REST else field(Field.HrRest, "30–120 bpm", hrRest.trim().toIntOrNull()?.takeIf { it in HR_REST })
        if (max != null && rest != null && max - rest < MIN_RESERVE) errors[Field.HrMax] = "At least 20 above resting"
        val profile = if (errors.isEmpty()) {
            Profile(born!!, sex, kg!!, cm!!, hrMax = max!!, hrRest = rest!!, zones = zones)
        } else null
        return Checked(errors, age?.let { "Age $it" }, "Not sure? We estimate ${estimate ?: "—"} from your age and update it when you go higher.", profile)
    }

    companion object {
        val AGE = 13..100
        val WEIGHT = 30.0..250.0
        val HEIGHT = 100.0..250.0
        val HR_MAX = 100..230
        val HR_REST = 30..120
        /** Max at least this far above rest: a smaller heart-rate reserve makes every zone a few bpm wide. */
        const val MIN_RESERVE = 20
        /** A blank resting heart rate: the profile's own default. */
        const val DEFAULT_REST = 60

        /** The form a saved profile opens as. */
        fun of(p: Profile) = ProfileForm(RunFormat.birthDate(p.birthDate), p.sex, RunFormat.number(p.weightKg), RunFormat.number(p.heightCm), p.hrMax.toString(), p.hrRest.toString())

        private fun decimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    }
}
