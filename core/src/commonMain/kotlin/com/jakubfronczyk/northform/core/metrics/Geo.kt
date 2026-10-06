package com.jakubfronczyk.northform.core.metrics

import com.jakubfronczyk.northform.core.LocationFix
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** `Core/Metrics/Geo.swift`. Same formula, same operation order, so totals match to the metre. */
object Geo {
    /** Mean Earth radius in metres (IUGG). */
    const val earthRadius = 6_371_008.8

    /** Great-circle distance in metres (haversine), `Geo.swift:8`. */
    fun distance(a: LocationFix, b: LocationFix): Double {
        val phi1 = a.latitude * PI / 180
        val phi2 = b.latitude * PI / 180
        val dPhi = phi2 - phi1
        val dLambda = (b.longitude - a.longitude) * PI / 180
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * earthRadius * asin(min(1.0, sqrt(h)))
    }
}
