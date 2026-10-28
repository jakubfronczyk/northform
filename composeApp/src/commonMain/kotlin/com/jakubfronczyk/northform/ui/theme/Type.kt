package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Text styles by role (`Theme.swift:39-56` and the artboards). Every size, weight and width the UI sets lives here; a
 * screen names a role. Numbers use tabular digits so a changing value doesn't jitter. The typeface is [Fonts]; a role's
 * width follows the artboard's `font-stretch` for it.
 */
object Type {
    /** A text style in the theme's typeface ([Fonts]); private so every size on screen is a named role below. */
    private fun text(size: Int, weight: FontWeight = FontWeight.Normal, family: FontFamily = Fonts.normal) =
        TextStyle(fontSize = size.sp, fontWeight = weight, fontFamily = family)

    /** A tabular-digit style; private so every number size on screen is a named role below. */
    private fun number(size: Int, weight: FontWeight = FontWeight.Bold, family: FontFamily = Fonts.normal) =
        text(size, weight, family).copy(fontFeatureSettings = "tnum")

    /** The artboards' `line-height: 1` on a big number: the line is the font size, trimmed, so a ring centres the digits. */
    private fun TextStyle.tight() = copy(
        lineHeight = fontSize,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

    /** The countdown's "Put your phone in your pocket". */
    val countdownHeadline = text(20, FontWeight.SemiBold)
    /** The cool-down's "0:38" inside its ring (`Cooldown.dc.html`: 72 px, 800, 78 %, line-height 1). */
    val cooldownLeft = number(72, FontWeight.ExtraBold, family = Fonts.narrowest).tight()
    /** The cool-down's "Now" heart rate (40 px, 700, 85 %, line-height 1). */
    val cooldownNow = number(40, FontWeight.Bold, family = Fonts.condensed).tight()
    /** A lead paragraph under a screen title: the cool-down's "Stand or walk slowly…" (16 px, line-height 1.4). */
    val lead = text(16).copy(lineHeight = 22.4.sp)
    /** " bpm" after a cool-down value (13 px, 500). */
    val smallUnit = text(13, FontWeight.Medium)
    /** The countdown's number inside its ring (`Countdown.dc.html`: 190 px, 800, 78 %, line-height 1). */
    val countdown = number(190, FontWeight.ExtraBold, family = Fonts.narrowest).tight()

    // Hoisted once: these sit on the 1 Hz live screen.
    val number54Black = number(54, FontWeight.Black, family = Fonts.narrowest)
    val number40 = number(40, family = Fonts.narrowest)
    val number24 = number(24, family = Fonts.condensed)
    val number22 = number(22, family = Fonts.condensed)
    val pillLabel = text(13, FontWeight.Bold)

    // Near-duplicate: `screenTitle` (28 Black), `pageTitle` (28 ExtraBold, tabular) and `heroTitle` (28 ExtraBold);
    // kept apart for the owner to decide.
    val screenTitle = text(28, FontWeight.Black, family = Fonts.condensed)
    val homeTitle = text(34, FontWeight.Black, family = Fonts.condensed)
    // Near-duplicate: `sheetTitle` (18 Bold) and `cardTitle` (17 Bold); kept apart for the owner to decide.
    val sheetTitle = text(18, FontWeight.Bold)
    /** A big button's label and the live screen's "Hold to stop": `sheetTitle`'s size, a touch narrower (90 %). */
    val buttonLabel = text(18, FontWeight.Bold, family = Fonts.semiCondensed)
    val rowLabel = text(14)
    val secondaryText = text(15)
    val tertiaryText = text(13)
    val statusLabel = text(14, FontWeight.SemiBold)
    // Near-duplicate: `unitLabel` (15 Medium) and `liveUnit` (16 Medium); kept apart for the owner to decide.
    val unitLabel = text(15, FontWeight.Medium)

    /** The title of a pushed page: History, Settings, Profile, Zones. */
    val pageTitle = number(28, FontWeight.ExtraBold, family = Fonts.condensed)
    /** The Sensor screen's state headline ("Connected", "Can’t find it"). */
    val heroTitle = text(28, FontWeight.ExtraBold, family = Fonts.condensed)
    /** The Gate's "Ready to run" headline. */
    val gateTitle = text(24, FontWeight.ExtraBold, family = Fonts.condensed)
    /** The headline of a bottom sheet: the shared confirm, the crash prompt. */
    val sheetHeadline = text(22, FontWeight.Black, family = Fonts.condensed)
    /** Today's Run and Walk tile names. */
    val tileTitle = number(26, FontWeight.ExtraBold, family = Fonts.condensed)
    /** The distance on Today's last-recording card. */
    val cardDistance = number(28, FontWeight.ExtraBold, family = Fonts.narrow)
    /** A card's or section's title: Today's Recent, a sensor's name, Settings' Backup, the profile's Heart rate. */
    val cardTitle = text(17, FontWeight.Bold)
    /** A row's bold name: Today's sensor card, the Sensor screen's firmware warning. */
    val bodyBold = text(16, FontWeight.Bold)
    /** A row's title or a plain action: the Recent and History rows, the Gate rows, Start anyway, the cool-down's Skip, Delete run. */
    val bodyStrong = text(16, FontWeight.SemiBold)
    /** An inline action or emphasised line: Retry, See all history, the outlined buttons, the Gate's warning. */
    val secondaryStrong = text(15, FontWeight.SemiBold)
    /** A filled small button's label (Connect, Pair), the Gate's fix count, the Sensor cards' leads. */
    val secondaryBold = text(15, FontWeight.Bold)
    /** The smallest line: "Newest first", the zone names, the version, the live screen's hold hints. */
    val caption = text(12)
    /** The sub-line under a live big number ("reconnecting…", the distance sub). */
    val tertiaryStrong = tertiaryText.copy(fontWeight = FontWeight.SemiBold)
    /** A partial last km in the Summary's splits. */
    val splitPartial = rowLabel.copy(fontStyle = FontStyle.Italic)
    /** The live "Paused · 0:42" pill. */
    val pausedPill = number(17, FontWeight.Bold)
    /** A zone's number: its start in % on the zone editor, its bpm on Setup's zones step. */
    val zonePercent = number(17, FontWeight.Bold, family = Fonts.condensed)
    /** A zone's name in its coloured pill on Setup's zones step. */
    val zonePill = text(14, FontWeight.Bold)
    /** A row's plain title beside a control: the profile's "Birth date". */
    val rowTitle = text(16, FontWeight.Medium)
    /** A typed value or its placeholder in a profile field. */
    val fieldText = number(16, FontWeight.Normal)
    /** The zone editor's − and + symbols. */
    val stepperSymbol = number(20, FontWeight.Bold)
    /** " bpm" beside the Sensor screen's live heart rate. */
    val liveUnit = text(16, FontWeight.Medium)
    /** " km" after the distance on Today's last-recording card (a span: the caller colours it). */
    val cardUnit = SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    /** " km" after the distance on a Recent / History row (a span: the caller colours it). */
    val rowUnit = SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
