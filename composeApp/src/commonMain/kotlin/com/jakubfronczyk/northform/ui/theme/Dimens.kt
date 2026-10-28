package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.unit.dp

/** The recurring sizes of the artboards. One-off gaps and paddings stay where they are used. */
object Dimens {
    /** The screen's side margin. */
    val gutter = 16.dp
    /** A screen's content starts this far down (under the status bar). */
    val screenTop = 64.dp
    /** A screen's content ends this far up (over the home indicator). */
    val screenBottom = 50.dp
    /** How far a sheet over the map (the Gate, the live run) overlaps it. */
    val sheetOverlap = 22.dp
    /** A round or square icon-only control: Back, Today's gear. */
    val iconButton = 44.dp
    /** A text-only action row: the Gate's Start anyway, the crash prompt's Delete run. */
    val textButton = 44.dp
    /** A small filled button inside a row: the Gate's Connect / Pair, the Sensor screen's Pair. */
    val inlineButton = 44.dp
    /** A History filter pill. */
    val pill = 44.dp
    /** One option of the Summary's Type and RPE rows. */
    val option = 44.dp
    /** The sensor disc on Settings' Sensor card. */
    val sensorDisc = 44.dp
    /** The pace column of the Summary's splits. */
    val paceColumn = 44.dp
    /** A 48 dp secondary button: Settings' outlined buttons, the profile's Sex, See all history. */
    val secondaryButton = 48.dp
    /** A profile text field. */
    val field = 52.dp
    /** The time column of the Summary's total per zone. */
    val zoneTimeColumn = 52.dp
    /** A card row: Today's sensor card, a Gate readiness row. */
    val row = 64.dp
    /** A taller row: the Gate's "Sensor not connected" row, a zone editor row. */
    val rowTall = 68.dp
    /** The one action button of every artboard, and the live controls beside it. */
    val bigButton = 68.dp
    /** An outline's stroke: outlined buttons, pills, tiles, fields, round controls. */
    val stroke = 1.5.dp
}
