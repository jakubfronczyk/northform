package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import kotlin.time.Instant

/**
 * The profile form's fields as typed (`SetupProfile.dc.html`: Setup's "About you", first-launch-setup/01, and Settings'
 * editor, profile-settings/03), checked by one pure function so the rules are table-tested and the screen only shows
 * them. `Profile`'s own checks stay the last guard.
 */
data class ProfileForm(
    /** DD.MM.YYYY. */
    val birthDate: String,
    val sex: Sex,
    /** kg; a comma reads as the decimal point. */
    val weight: String,
    /** cm. */
    val height: String,
    /** Blank: estimated from the age in Setup ([Blanks.Filled]), a mistake in Settings. */
    val hrMax: String,
    /** Blank: [Profile.DEFAULT_REST] in Setup ([Blanks.Filled]), a mistake in Settings. */
    val hrRest: String,
) {
    enum class Field { BirthDate, Weight, Height, HrMax, HrRest }

    /** How a blank field reads: the one rule that differs between the form's two contexts. */
    enum class Blanks {
        /**
         * Setup's first step: a blank max saves the estimate from the age (D54), a blank resting saves
         * [Profile.DEFAULT_REST], and a field not typed yet is not a mistake, so it shows no message.
         */
        Filled,
        /** Settings' editor, which opens on a saved profile: every field is required, so a cleared one says so. */
        Required,
    }

    /**
     * [errors]: the message to show under each field (none for a blank field in [Blanks.Filled]); [profile] only when the
     * form is valid (Save / Continue is enabled exactly then). [hint] and [maxPlaceholder] are Setup's live estimate
     * ("Not sure? We estimate 187…", "—" until the birth date gives an age); null in [Blanks.Required].
     */
    data class Checked(val errors: Map<Field, String>, val ageHint: String?, val hint: String?, val maxPlaceholder: String?, val profile: Profile?)

    /** @param now and [tzOffset] are the phone's (the age); [zones] are kept as they are (the zone editor owns them). */
    fun check(now: Instant, tzOffset: Int, zones: HRZones, blanks: Blanks): Checked {
        val errors = LinkedHashMap<Field, String>()
        val filled = blanks == Blanks.Filled
        // Setup: a field not typed yet keeps the form from being a profile without a message of its own.
        var incomplete = false
        // Each value, or null with its field's message recorded (or, blank in Setup, only marked incomplete).
        fun <T> field(field: Field, text: String, message: String, value: T?): T? = value.also {
            if (it == null) { if (filled && text.isBlank()) incomplete = true else errors[field] = message }
        }
        val born = field(Field.BirthDate, birthDate, "Use DD.MM.YYYY", RunFormat.parseBirthDate(birthDate))
        val age = born?.let { RunFormat.age(it, now, tzOffset) }
        if (age != null && age !in AGE) errors[Field.BirthDate] = "Age must be 13–100"
        val kg = field(Field.Weight, weight, "30–250 kg", decimal(weight)?.takeIf { it in WEIGHT })
        val cm = field(Field.Height, height, "100–250 cm", decimal(height)?.takeIf { it in HEIGHT })
        val estimate = age?.takeIf { it in AGE }?.let(Profile::estimatedHrMax)
        // A blank max in Setup is the estimate; with none, the birth date is blank or invalid and says so itself.
        val max = if (filled && hrMax.isBlank()) estimate.also { if (it == null) incomplete = true }
                  else field(Field.HrMax, hrMax, "100–230 bpm", hrMax.trim().toIntOrNull()?.takeIf { it in HR_MAX })
        val rest = if (filled && hrRest.isBlank()) Profile.DEFAULT_REST
                   else field(Field.HrRest, hrRest, "30–120 bpm", hrRest.trim().toIntOrNull()?.takeIf { it in HR_REST })
        if (max != null && rest != null && max - rest < MIN_RESERVE) errors[Field.HrMax] = "At least 20 above resting"
        val profile = if (errors.isEmpty() && !incomplete) {
            Profile(born!!, sex, kg!!, cm!!, hrMax = max!!, hrRest = rest!!, zones = zones)
        } else null
        return Checked(
            errors = errors,
            ageHint = age?.let { "Age $it" },
            hint = if (filled) "Not sure? We estimate ${estimate ?: "—"} from your age and update it when you go higher. Resting defaults to ${Profile.DEFAULT_REST}." else null,
            maxPlaceholder = if (filled) estimate?.toString() else null,
            profile = profile,
        )
    }

    companion object {
        val AGE = 13..100
        val WEIGHT = 30.0..250.0
        val HEIGHT = 100.0..250.0
        val HR_MAX = 100..230
        val HR_REST = 30..120
        /** Max at least this far above rest: a smaller heart-rate reserve makes every zone a few bpm wide. */
        const val MIN_RESERVE = 20

        /** Setup's first step on a fresh install: nothing typed, Male preselected as on the artboard. */
        val blank = ProfileForm("", Sex.Male, "", "", "", "")

        /** The form a saved profile opens as. */
        fun of(p: Profile) = ProfileForm(RunFormat.birthDate(p.birthDate), p.sex, RunFormat.number(p.weightKg), RunFormat.number(p.heightCm), p.hrMax.toString(), p.hrRest.toString())

        private fun decimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
    }
}
