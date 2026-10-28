package com.jakubfronczyk.northform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jakubfronczyk.northform.core.HRZones
import com.jakubfronczyk.northform.core.Profile
import com.jakubfronczyk.northform.core.Sex
import com.jakubfronczyk.northform.ui.ProfileForm.Blanks
import com.jakubfronczyk.northform.ui.ProfileForm.Field
import com.jakubfronczyk.northform.ui.theme.Dimens
import com.jakubfronczyk.northform.ui.theme.Shapes
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.theme.Type
import kotlin.time.Instant

/**
 * The profile editor (`SetupProfile.dc.html` in its edit context, profile-settings/03): every field required and checked
 * as typed by [ProfileForm], its message under it; Save only when all are valid; Back discards. The zones are kept.
 */
@Composable
fun ProfileEditScreen(initial: Profile, now: Instant, tzOffset: Int, onSave: (Profile) -> Unit, back: () -> Unit) =
    ProfileFormScreen(
        ProfileForm.of(initial), initial.zones, now, tzOffset, Blanks.Required,
        title = "Profile", subtitle = "Used for your heart rate zones.",
        button = "Save", buttonFill = Theme.primary, buttonText = Theme.onPrimary, onSubmit = onSave,
    ) {
        Row(Modifier.height(Dimens.iconButton), verticalAlignment = Alignment.CenterVertically) { BackButton("Back to Settings", back) }
    }

/**
 * The same form as Setup's first step, "About you" (`SetupProfile.dc.html`, first-launch-setup/01): [initial] is blank on a
 * fresh install, the saved profile when the runner comes back to it; a blank max or resting is filled in
 * ([Blanks.Filled]); Continue submits. [header] is Setup's progress.
 */
@Composable
fun SetupAboutYouScreen(initial: ProfileForm, zones: HRZones, now: Instant, tzOffset: Int, onContinue: (Profile) -> Unit, header: @Composable () -> Unit) =
    ProfileFormScreen(
        initial, zones, now, tzOffset, Blanks.Filled,
        title = "About you", subtitle = "Used for your heart rate zones. You can change it later.",
        button = "Continue", buttonFill = Theme.light, buttonText = Theme.onLight, onSubmit = onContinue, header = header,
    )

/**
 * The profile form in either context; the caller says everything that differs: the words, the button's colours, and how
 * blank fields read ([blanks]). Laid out as the artboard's two cards, except that max and resting heart rate sit side by
 * side under one hint (the artboard stacks them), so the whole form fits the iPhone 16e with its button pinned.
 */
@Composable
private fun ProfileFormScreen(
    initial: ProfileForm, zones: HRZones, now: Instant, tzOffset: Int, blanks: Blanks,
    title: String, subtitle: String, button: String, buttonFill: Color, buttonText: Color,
    onSubmit: (Profile) -> Unit, header: @Composable () -> Unit,
) {
    var form by remember(initial) { mutableStateOf(initial) }
    val checked = form.check(now, tzOffset, zones, blanks)
    val profile = checked.profile
    PinnedScreen(button = {
        BigButton(
            button, { if (profile != null) onSubmit(profile) }, enabled = profile != null,
            fill = if (profile != null) buttonFill else Theme.track, textColor = if (profile != null) buttonText else Theme.dim,
        )
    }) {
        header()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Type.pageTitle, color = Theme.text)
            Text(subtitle, style = Type.secondaryText, color = Theme.text2)
        }
        FormCard {
            BirthDate(form.birthDate, checked.errors[Field.BirthDate], checked.ageHint) { form = form.copy(birthDate = it) }
            HDivider()
            SexPicker(form.sex) { form = form.copy(sex = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Input("Weight", form.weight, checked.errors[Field.Weight], "kg", Modifier.weight(1f), keyboard = KeyboardType.Decimal) { form = form.copy(weight = it) }
                Input("Height", form.height, checked.errors[Field.Height], "cm", Modifier.weight(1f), keyboard = KeyboardType.Decimal) { form = form.copy(height = it) }
            }
        }
        FormCard {
            Text("Heart rate", style = Type.cardTitle, color = Theme.text)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Input("Max heart rate", form.hrMax, checked.errors[Field.HrMax], "bpm", Modifier.weight(1f), placeholder = checked.maxPlaceholder) { form = form.copy(hrMax = it) }
                    Input("Resting heart rate", form.hrRest, checked.errors[Field.HrRest], "bpm", Modifier.weight(1f)) { form = form.copy(hrRest = it) }
                }
                checked.hint?.let { Text(it, style = Type.caption, color = Theme.text3) }
            }
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(Shapes.card).background(Theme.card).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
)

/** "Birth date" with the age (or what is wrong) under it, and the date in its calendar control on the right. */
@Composable
private fun BirthDate(value: String, error: String?, age: String?, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.field), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Birth date", style = Type.rowTitle, color = Theme.text)
            when {
                error != null -> Text(error, style = Type.tertiaryText, color = Theme.warn)
                age != null -> Text(age, style = Type.tertiaryText, color = Theme.text2)
            }
        }
        Row(
            Modifier.height(Dimens.inlineButton).clip(Shapes.inlineButton).background(Theme.track)
                .border(Dimens.stroke, if (error != null) Theme.warn else Theme.track, Shapes.inlineButton).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfIcon(NfIcons.calendar, Theme.text2, size = Dimens.iconSmall)
            Field(value, "Birth date", Type.bodyStrong, "DD.MM.YYYY", KeyboardType.Text, Modifier.width(Dimens.dateField), onChange)
        }
    }
}

@Composable
private fun SexPicker(sex: Sex, onPick: (Sex) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sex", style = Type.tertiaryText, color = Theme.text2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in Sex.entries) {
                val on = option == sex
                Box(
                    Modifier.weight(1f).height(Dimens.pill).clip(Shapes.pill)
                        .then(if (on) Modifier.background(Theme.text) else Modifier.border(Dimens.stroke, Theme.outline, Shapes.pill))
                        .clickable { onPick(option) }
                        .semantics { selected = on; role = Role.RadioButton },
                    contentAlignment = Alignment.Center,
                ) { Text(option.name, style = Type.secondaryStrong, color = if (on) Theme.bg else Theme.text) }
            }
        }
    }
}

/** A labelled text field with its unit, outlined in amber with its message under it when invalid. */
@Composable
private fun Input(
    label: String, value: String, error: String?, unit: String, modifier: Modifier = Modifier, placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Number, onChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.tertiaryText, color = Theme.text2, maxLines = 1)
        Row(
            Modifier.fillMaxWidth().height(Dimens.field).clip(Shapes.field).background(Theme.inset)
                .border(Dimens.stroke, if (error != null) Theme.warn else Theme.outline, Shapes.field).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(value, label, Type.fieldText, placeholder, keyboard, Modifier.weight(1f), onChange)
            Text(unit, style = Type.secondaryText, color = Theme.text2)
        }
        error?.let { Text(it, style = Type.tertiaryText, color = Theme.warn) }
    }
}

/** One line of typed text, its [placeholder] in grey while empty. */
@Composable
private fun Field(value: String, label: String, style: TextStyle, placeholder: String?, keyboard: KeyboardType, modifier: Modifier, onChange: (String) -> Unit) =
    BasicTextField(
        value, onChange, singleLine = true, modifier = modifier.semantics { contentDescription = label },
        textStyle = style.copy(color = Theme.text), cursorBrush = SolidColor(Theme.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = Theme.text3, maxLines = 1)
                field()
            }
        },
    )
