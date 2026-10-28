package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * The app's one typeface: Archivo (OFL, `resources/fonts/OFL.txt`), the variable font from google/fonts with a weight
 * (100–900) and a width (62–125 %) axis. Swap the file and [FILE] here to retheme every role in [Type].
 *
 * A width is a family of its own: Compose matches a style's weight to a [Font], and the variation settings ride on that
 * font, so each width the artboards set (`font-stretch`) is one family with one instance per weight. The file is read
 * once, lazily, on the first text layout; nothing has to run at boot.
 */
internal object Fonts {
    private const val FILE = "Archivo[wdth,wght]"

    private val bytes by lazy { readBundledFont(FILE, "ttf") }

    private val weights = listOf(
        FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    )

    private fun family(width: Float) = FontFamily(
        weights.map { weight ->
            Font(
                identity = "$FILE-w$width-${weight.weight}",
                getData = { bytes },
                weight = weight,
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(weight, FontStyle.Normal, FontVariation.width(width)),
            )
        },
    )

    /** Body text, labels, captions: the font's normal width. */
    val normal = family(100f)
    /** Button labels and the hold hint (`font-stretch: 90%`). */
    val semiCondensed = family(90f)
    /** Titles, tile names, mid-size numbers (`font-stretch: 85%`). */
    val condensed = family(85f)
    /** Today's last-recording distance (`font-stretch: 80%`). */
    val narrow = family(80f)
    /** The big live and summary numbers, the countdown (`font-stretch: 78%`). */
    val narrowest = family(78f)
}

/** Reads a font file shipped in the app bundle; a leaf factory, one per platform. */
internal expect fun readBundledFont(name: String, extension: String): ByteArray
