package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.graphics.Color
import com.jakubfronczyk.northform.core.RecordingType

/**
 * Colour tokens from the style guide (`Features/Style/Theme.swift:4-26`) and the artboards. Every colour the UI draws
 * lives here; a screen names a role, never a hex value.
 */
object Theme {
    val bg = Color(0xFF161718)
    val card = Color(0xFF232426)
    val track = Color(0xFF2E2F32)
    val line = Color(0xFF34363A)
    val text = Color(0xFFF4F4F2)
    val text2 = Color(0xFFA9AAA6)
    val text3 = Color(0xFF8E8F8B)
    /** A disabled control's ink. The same grey as [text3], kept as its own role so disabled can change on its own. */
    val dim = text3
    val ring = Color(0xFF6A6B6E)
    /** The outline of an unselected pill and the Walk tile (`#707175` on the Today and History artboards). */
    val outline = Color(0xFF707175)
    val accent = Color(0xFFFF6B2C)
    val primary = Color(0xFFC2461C)
    val warn = Color(0xFFE9C46A)
    val ok = Color(0xFF5FD08A)

    /** Ink on a primary fill: the Run tile, Start, Connect / Pair, the sensor's filled button, the crash prompt's Save. */
    val onPrimary = Color.White
    /** A light main button (Setup's Continue and Skip, the Sensor screen's Done): the text colour as its fill. */
    val light = text
    /** Ink on a [light] fill. */
    val onLight = bg
    /** A field or an inset box inside a card: the profile's text fields, the Pair step's firmware warning (#1B1C1E). */
    val inset = Color(0xFF1B1C1E)
    /** The red of a destructive word: the crash prompt's Delete run, Settings' Forget sensor. */
    val danger = Color(0xFFFF6961)
    /** The filled confirm of the shared destructive sheet (`DeleteConfirm.dc.html`). */
    val destructive = Color(0xFFB83A2E)
    /** The dim behind a bottom sheet: the shared confirm, the crash prompt (black at 60 %). */
    val scrim = Color(0x99000000)
    /** The map placeholder (LiveDark / Gate artboards) and Today's last-recording thumbnail. */
    val panel = Color(0xFF1D1F21)
    /** An unlit segment: the live zone bar, the Gate's GPS fix bars. */
    val unlit = Color(0xFF3A3B3E)

    /** The see-through disc or capsule over the map: the Gate's and reopened Summary's Back, the chips, Re-center. */
    val overlay = bg.copy(alpha = 0.85f)
    /** The turning quarter of the Gate's static spinner ring. */
    val spinnerArc = text.copy(alpha = 0.6f)
    /** The border of the Summary's HRmax card. */
    val accentBorder = accent.copy(alpha = 0.5f)
    /** The halo around the route end on the Compose-drawn map. */
    val accentHalo = accent.copy(alpha = 0.22f)
    /** The shade over a heart-rate gap on the Summary chart. */
    val gapShade = bg.copy(alpha = 0.55f)
    /** Alpha of a zone's band behind the Summary heart-rate line (applied to [zoneColors]). */
    const val BAND_ALPHA = 0.18f
    /** Alpha of the Sensor hero's outer ring (applied to the state's ring colour). */
    const val HALO_OUTER_ALPHA = 0.18f
    /** Alpha of the Sensor hero's inner, dashed ring. */
    const val HALO_INNER_ALPHA = 0.35f

    /** A recording type's colour (the gate chip, Today's dots and thumbnail): a run in the accent, a walk in grey. */
    fun of(type: RecordingType) = if (type == RecordingType.Run) accent else text2

    /** Zone colours by number, Z0 first. The names live on `core` `Zone.name` (D29): one table. */
    val zoneColors = listOf(
        Color(0xFF6B6C70),
        Color(0xFF9CA3AF),
        Color(0xFF3B82F6),
        Color(0xFF22C55E),
        Color(0xFFF59E0B),
        Color(0xFFEF4444),
    )

    /** Ink on a zone's colour (Setup's zone pills): light on Z0's grey, dark on the rest (`SetupZones.dc.html`). */
    fun onZone(zone: Int) = if (zone == 0) text else bg
}
