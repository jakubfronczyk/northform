package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Text styles by role (`Theme.swift:39-56` and the artboards). Every size and weight the UI sets lives here; a screen
 * names a role. Numbers use tabular digits so a changing value doesn't jitter.
 */
object Type {
    /** A tabular-digit style; private so every number size on screen is a named role below. */
    private fun number(size: Int, weight: FontWeight = FontWeight.Bold) =
        TextStyle(fontSize = size.sp, fontWeight = weight, fontFeatureSettings = "tnum")

    // Hoisted once: these sit on the 1 Hz live screen.
    val countdown = number(160, FontWeight.Black)
    val number54Black = number(54, FontWeight.Black)
    val number40 = number(40)
    val number24 = number(24)
    val number22 = number(22)
    val pillLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)

    // Near-duplicate: `screenTitle` (28 Black), `pageTitle` (28 ExtraBold, tabular) and `heroTitle` (28 ExtraBold);
    // kept apart for the owner to decide.
    val screenTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black)
    val homeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black)
    // Near-duplicate: `sheetTitle` (18 Bold) and `cardTitle` (17 Bold); kept apart for the owner to decide.
    val sheetTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val rowLabel = TextStyle(fontSize = 14.sp)
    val secondaryText = TextStyle(fontSize = 15.sp)
    val tertiaryText = TextStyle(fontSize = 13.sp)
    val statusLabel = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    // Near-duplicate: `unitLabel` (15 Medium) and `liveUnit` (16 Medium); kept apart for the owner to decide.
    val unitLabel = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

    /** The title of a pushed page: History, Settings, Profile, Zones. */
    val pageTitle = number(28, FontWeight.ExtraBold)
    /** The Sensor screen's state headline ("Connected", "Can’t find it"). */
    val heroTitle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    /** The Gate's "Ready to run" headline. */
    val gateTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    /** The headline of a bottom sheet: the shared confirm, the crash prompt. */
    val sheetHeadline = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black)
    /** Today's Run and Walk tile names. */
    val tileTitle = number(26, FontWeight.ExtraBold)
    /** The distance on Today's last-recording card. */
    val cardDistance = number(28, FontWeight.ExtraBold)
    /** A card's or section's title: Today's Recent, a sensor's name, Settings' Backup, the profile's Heart rate. */
    val cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    /** A row's bold name: Today's sensor card, the Sensor screen's firmware warning. */
    val bodyBold = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
    /** A row's title or a plain action: the Recent and History rows, the Gate rows, Start anyway, Delete run. */
    val bodyStrong = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    /** An inline action or emphasised line: Retry, See all history, the outlined buttons, the Gate's warning. */
    val secondaryStrong = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    /** A filled small button's label (Connect, Pair), the Gate's fix count, the Sensor cards' leads. */
    val secondaryBold = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    /** The smallest line: "Newest first", the zone names, the version, the live screen's hold hints. */
    val caption = TextStyle(fontSize = 12.sp)
    /** The sub-line under a live big number ("reconnecting…", the distance sub). */
    val tertiaryStrong = tertiaryText.copy(fontWeight = FontWeight.SemiBold)
    /** A partial last km in the Summary's splits. */
    val splitPartial = rowLabel.copy(fontStyle = FontStyle.Italic)
    /** The live "Paused · 0:42" pill. */
    val pausedPill = number(17, FontWeight.Bold)
    /** A zone's number: its start in % on the zone editor, its bpm on Setup's zones step. */
    val zonePercent = number(17, FontWeight.Bold)
    /** A zone's name in its coloured pill on Setup's zones step. */
    val zonePill = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    /** A row's plain title beside a control: the profile's "Birth date". */
    val rowTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** A typed value or its placeholder in a profile field. */
    val fieldText = number(16, FontWeight.Normal)
    /** The zone editor's − and + symbols. */
    val stepperSymbol = number(20, FontWeight.Bold)
    /** " bpm" beside the Sensor screen's live heart rate. */
    val liveUnit = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    /** " km" after the distance on Today's last-recording card (a span: the caller colours it). */
    val cardUnit = SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    /** " km" after the distance on a Recent / History row (a span: the caller colours it). */
    val rowUnit = SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
