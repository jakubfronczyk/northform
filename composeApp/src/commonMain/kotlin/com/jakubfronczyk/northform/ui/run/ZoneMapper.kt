package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.engine.run.RunSnapshot

/**
 * The one place the run screens read the snapshot's zone. D23 turns `RunSnapshot.zone` into a
 * `Zone` value type (landed): the screens keep an `Int` number, the pill reads the `Zone` itself.
 * Kotlin idiom: an extension property keeps the mapping out of the frozen snapshot.
 */
internal val RunSnapshot.zoneNumber: Int?
    get() = zone?.number
