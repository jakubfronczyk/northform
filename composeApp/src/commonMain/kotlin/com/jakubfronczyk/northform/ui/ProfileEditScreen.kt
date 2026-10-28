package com.jakubfronczyk.northform.ui

import com.jakubfronczyk.northform.core.HRZones
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.ProfileForm.Field
import kotlin.time.Instant
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type

/**
 * The profile editor (`SetupProfile.dc.html` in its edit context, profile-settings/03): every field checked as typed
 * by [ProfileForm], its message under it; Save only when all are valid; Back discards. The zones are kept as they are.
 */
@Composable
fun ProfileEditScreen(initial: Profile, now: Instant, tzOffset: Int, onSave: (Profile) -> Unit, back: () -> Unit) =
    ProfileFormScreen(ProfileForm.of(initial), initial.zones, now, tzOffset, setup = false, onSubmit = onSave) {
        Box(Modifier.offset(x = (-10).dp).size(Dimens.iconButton).clickable(onClick = back), contentAlignment = Alignment.Center) {
            NfIcon(NfIcons.back, Theme.text, size = 22.dp, label = "Back to Settings")
        }
    }

/**
 * The same form as Setup's first step, "About you" (`SetupProfile.dc.html`, first-launch-setup/01): [initial] is blank on a
 * fresh install, the saved profile when the runner comes back to it; Continue submits. [header] is Setup's progress.
 */
@Composable
fun SetupAboutYouScreen(initial: ProfileForm, zones: HRZones, now: Instant, tzOffset: Int, onContinue: (Profile) -> Unit, header: @Composable () -> Unit) =
    ProfileFormScreen(initial, zones, now, tzOffset, setup = true, onSubmit = onContinue, header = header)

/** The profile form in its two contexts: Settings' editor ("Profile", Save) and Setup's first step ("About you", Continue). */
@Composable
private fun ProfileFormScreen(
    initial: ProfileForm, zones: HRZones, now: Instant, tzOffset: Int, setup: Boolean,
    onSubmit: (Profile) -> Unit, header: @Composable () -> Unit,
) {
    var form by remember(initial) { mutableStateOf(initial) }
    val checked = form.check(now, tzOffset, zones)
    // An empty field shows no error yet (nothing typed is not a mistake); Save / Continue stays off until all are valid.
    fun shown(field: Field, value: String) = checked.errors[field]?.takeIf { value.isNotBlank() }
    val profile = checked.profile
    PinnedScreen(button = {
        BigButton(if (setup) "Continue" else "Save", { if (profile != null) onSubmit(profile) }, enabled = profile != null, fill = if (profile != null) Theme.primary else Theme.track, textColor = if (profile != null) Theme.text else Theme.dim)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            Text(if (setup) "About you" else "Profile", style = Type.pageTitle, color = Theme.text)
            Text(if (setup) "Used for your heart rate zones. You can change it later." else "Used for your heart rate zones.", style = Type.secondaryText, color = Theme.text2)
        }
        Input("Birth date", form.birthDate, shown(Field.BirthDate, form.birthDate), hint = checked.ageHint, keyboard = KeyboardType.Text) { form = form.copy(birthDate = it) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sex", style = Type.tertiaryText, color = Theme.text2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sex in Sex.entries) {
                    val on = form.sex == sex
                    Box(
                        Modifier.weight(1f).height(Dimens.secondaryButton).clip(Shapes.pill)
                            .then(if (on) Modifier.background(Theme.text) else Modifier.border(Dimens.stroke, Theme.outline, Shapes.pill))
                            .clickable { form = form.copy(sex = sex) }
                            .semantics { selected = on; role = Role.RadioButton },
                        contentAlignment = Alignment.Center,
                    ) { Text(sex.name, style = Type.secondaryStrong, color = if (on) Theme.bg else Theme.text) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Input("Weight", form.weight, shown(Field.Weight, form.weight), unit = "kg", modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal) { form = form.copy(weight = it) }
            Input("Height", form.height, shown(Field.Height, form.height), unit = "cm", modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal) { form = form.copy(height = it) }
        }
        Text("Heart rate", style = Type.cardTitle, color = Theme.text)
        // Max and resting side by side, like weight and height, so the whole form fits the screen; one hint under both.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Input("Max", form.hrMax, shown(Field.HrMax, form.hrMax), unit = "bpm", modifier = Modifier.weight(1f)) { form = form.copy(hrMax = it) }
                Input("Resting", form.hrRest, shown(Field.HrRest, form.hrRest), unit = "bpm", modifier = Modifier.weight(1f)) { form = form.copy(hrRest = it) }
            }
            Text("${checked.maxHint} Resting defaults to 60.", style = Type.caption, color = Theme.text3)
        }
    }
}

/** A labelled text field with its unit, its message in amber when invalid, or a grey [hint]. */
@Composable
private fun Input(
    label: String, value: String, error: String?, modifier: Modifier = Modifier, unit: String? = null, hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Number, onChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.tertiaryText, color = Theme.text2)
        Row(
            Modifier.fillMaxWidth().height(Dimens.field).clip(Shapes.field).background(Theme.card)
                .border(Dimens.stroke, if (error != null) Theme.warn else Theme.card, Shapes.field).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = label },
                textStyle = Type.fieldValue.copy(color = Theme.text), cursorBrush = SolidColor(Theme.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
            unit?.let { Text(it, style = Type.tertiaryText, color = Theme.text2) }
        }
        when {
            error != null -> Text(error, style = Type.tertiaryText, color = Theme.warn)
            hint != null -> Text(hint, style = Type.tertiaryText, color = Theme.text3)
        }
    }
}
