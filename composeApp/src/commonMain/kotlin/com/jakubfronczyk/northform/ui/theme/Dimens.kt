package com.jakubfronczyk.northform.ui.theme

import androidx.compose.ui.unit.dp

/** The recurring sizes of the artboards. One-off gaps and paddings stay where they are used. */
object Dimens {
    /** Between the blocks of a form-like screen (Setup, the editors): the artboards' 18; every step still fits the 16e. */
    val blockGap = 18.dp
    /** Above a [com.jakubfronczyk.northform.ui.PinnedScreen]'s pinned button. */
    val buttonGap = 12.dp
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
    /** A selectable or outlined pill: a History filter, the profile's Sex, Setup's Edit boundaries. */
    val pill = 44.dp
    /** One option of the Summary's Type and RPE rows. */
    val option = 44.dp
    /** The sensor disc on Settings' Sensor card. */
    val sensorDisc = 44.dp
    /** The pace column of the Summary's splits. */
    val paceColumn = 44.dp
    /** A 48 dp secondary button: Settings' outlined buttons, See all history. */
    val secondaryButton = 48.dp
    /** A profile text field (`SetupProfile.dc.html`). */
    val field = 48.dp
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

    // Icons and small marks, by the size the artboards draw them.
    /** A header or row icon: Back, the permission glyphs, a sensor glyph in its 44 dp circle. */
    val icon = 22.dp
    /** A sensor glyph in a nearby row's 40 dp circle, the firmware warning's triangle. */
    val iconMedium = 20.dp
    /** A small inline icon: the tick beside "Allowed", a fact's tick or warning, the calendar, the scanning spinner. */
    val iconSmall = 18.dp
    /** The warning triangle before a permission's "Not allowed". */
    val iconTiny = 16.dp
    /** The circle behind a nearby sensor's glyph (`SetupPair.dc.html` scanning rows). */
    val nearbyDisc = 40.dp
    /** A status dot: "Paired", the Sensor screen's live heart-rate dot. */
    val statusDot = 8.dp
    /** A zone's colour dot on the zone editor. */
    val zoneDot = 10.dp
    /** The zone editor's round ±1 stepper. */
    val stepper = 36.dp
    /** A 10 dp bar (`Shapes.barTall`): the zone editor's preview. */
    val barTall = 10.dp
    /** One of Setup's four progress segments. */
    val progressSegment = 4.dp
    /** A zone's coloured pill on Setup's zones step. */
    val zonePill = 28.dp
    /** The typed birth date inside its control, wide enough for DD.MM.YYYY. */
    val dateField = 112.dp
    /** A Setup card row with a fact or permission (`min-height: 56px`). */
    val factRow = 56.dp
    /** A zones-step table row (`min-height: 52px`). */
    val zoneRow = 52.dp
    /** The zones step's ZONE column (`grid-template-columns: 140px …`). */
    val zoneColumn = 140.dp
    /** The zones step's BPM column (`… 96px`). */
    val bpmColumn = 96.dp

    // The run flow's rings (`Countdown.dc.html`, `Cooldown.dc.html`): the box the ring sits in, its circle, its stroke.
    /** The countdown ring's 300 dp box (the svg's size); the circle inside is [countdownRing]. */
    val countdownRingBox = 300.dp
    /** The countdown ring's circle: r 130, so 260 dp across the stroke's centre line. */
    val countdownRing = 260.dp
    /** The countdown ring's stroke. */
    val countdownRingStroke = 10.dp
    /** One of the countdown's five tick dots. */
    val tickDot = 10.dp
    /** The cool-down ring's 260 dp box; the circle inside is [cooldownRing]. */
    val cooldownRingBox = 260.dp
    /** The cool-down ring's circle: r 112, so 224 dp across the stroke's centre line. */
    val cooldownRing = 224.dp
    /** The cool-down ring's stroke. */
    val cooldownRingStroke = 12.dp
    /** The cool-down's heart-rate-since-stop chart. */
    val recoveryChart = 44.dp
    /** The cool-down's "Sensor lost" triangle. */
    val iconMini = 14.dp
}
