package com.jakubfronczyk.northform.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner radii by role (the artboards' `border-radius`). Every rounded shape the UI draws lives here. */
object Shapes {
    /** A card on Today, Settings, the zone editor, the Gate and the Sensor screen. */
    val card = RoundedCornerShape(22.dp)
    /** A selectable pill: History's filters, the profile's Sex. */
    val pill = RoundedCornerShape(22.dp)
    /** A Summary card (Heart rate, Splits, Your run, the HRmax card). */
    val summaryCard = RoundedCornerShape(18.dp)
    /** The live "Paused" pill. */
    val pausedPill = RoundedCornerShape(18.dp)
    /** The 68 dp action button and the live hold-to-stop bar. */
    val bigButton = RoundedCornerShape(16.dp)
    /** A profile text field. */
    val field = RoundedCornerShape(14.dp)
    /** Settings' 48 dp outlined button (Forget sensor, Export backup). */
    val button = RoundedCornerShape(14.dp)
    /** Today's last-recording thumbnail. */
    val thumbnail = RoundedCornerShape(14.dp)
    /** A small control inside a row: the Gate's Connect / Pair, Pair, Allow, Open Settings, the birth-date control. */
    val inlineButton = RoundedCornerShape(12.dp)
    /** One option of the Summary's Type and RPE rows. */
    val option = RoundedCornerShape(12.dp)
    /** The Summary's Notes box. */
    val textArea = RoundedCornerShape(12.dp)
    /** The Summary's bin button. */
    val iconTile = RoundedCornerShape(10.dp)
    // Near-duplicate: `bar` (4 dp) and `barTall` (5 dp); kept apart for the owner to decide.
    /** An 8 dp bar or segment: Settings' zone bar, the live zone bar, the splits' pace bar. */
    val bar = RoundedCornerShape(4.dp)
    /** A 10 dp bar: the zone editor's preview, the Summary's time per zone. */
    val barTall = RoundedCornerShape(5.dp)
    /** The square inside the paused screen's stop circle. */
    val stopSquare = RoundedCornerShape(4.dp)
    /** One of the Gate's GPS fix bars. */
    val fixSegment = RoundedCornerShape(3.dp)
    /** One of the two drawn pause bars. */
    val pauseBar = RoundedCornerShape(2.dp)
    /** One of Setup's four 4 dp progress segments. */
    val progressSegment = RoundedCornerShape(2.dp)
    /** A box inset in a card: the Pair step's firmware warning. */
    val inset = RoundedCornerShape(16.dp)
    /** A bottom sheet's top corners: the confirm sheet, the crash prompt, the Gate and live sheets over the map. */
    val sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    // Near-duplicate: `capsule` (50 %) and `tag` (999 dp) both draw fully round ends; kept apart for the owner to decide.
    /** Fully round ends at any height: the map chips, Re-center, the zone pill. */
    val capsule = RoundedCornerShape(50)
    /** The Summary's type tag ("Run"). */
    val tag = RoundedCornerShape(999.dp)
    /** The Gate spinner's turning half: the top two corners round, the bottom square. */
    val spinnerArc = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 0, bottomStartPercent = 0)
}
