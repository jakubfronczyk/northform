package com.jakubfronczyk.northform.ui.run

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** A camera offset from the route's end, in metres (east, north). */
data class Pan(val eastMetres: Double = 0.0, val northMetres: Double = 0.0) {
    operator fun plus(other: Pan) = Pan(eastMetres + other.eastMetres, northMetres + other.northMetres)
    val isZero get() = eastMetres == 0.0 && northMetres == 0.0
}

/**
 * Where the live map looks (U5, Swift run-screen-map/02): following the route's end until the user
 * drags, then held where they left it until "Re-center". Compose `mutableStateOf` so the screen
 * recomposes the re-center button and the canvas on each change; the rule itself is plain Kotlin.
 */
class MapCamera {
    var following by mutableStateOf(true)
        private set
    var pan by mutableStateOf(Pan())
        private set

    /** A finger moved the map: stop following, keep the offset. */
    fun dragBy(delta: Pan) {
        following = false
        pan += delta
    }

    fun recenter() {
        following = true
        pan = Pan()
    }
}

/**
 * The one camera the run flow shares with whatever draws the map. A `CompositionLocal` rather than a
 * parameter so the platform map (`:app/RouteMap.kt`, slot type `(Route) -> Unit`) can adopt it later
 * without a signature change; the default is a camera nobody drags.
 */
val LocalMapCamera = compositionLocalOf { MapCamera() }

/**
 * Lat/lon → canvas pixels, pure (table-tested): an equirectangular projection around the camera
 * centre (route end + pan). Fine at run scale — a 900 m window, the same as `RouteMap.kt`'s
 * `MKCoordinateRegionMakeWithDistance`. Mercator would change nothing visible over 1 km.
 */
object RouteProjection {
    /** Metres across the shorter side of the view. */
    const val SPAN_METRES = 900.0
    private const val METRES_PER_DEGREE_LAT = 111_320.0

    data class Px(val x: Float, val y: Float)

    /** Metres per pixel at this view size. */
    fun metresPerPixel(widthPx: Float, heightPx: Float): Double = SPAN_METRES / min(widthPx, heightPx).coerceAtLeast(1f)

    /** The route's last point, or null for a route with nothing drawn yet. */
    fun end(route: Route): RoutePoint? = route.segments.lastOrNull()?.lastOrNull()

    /** The camera centre as lat/lon: the route end moved by the pan. */
    fun centre(route: Route, pan: Pan): Pair<Double, Double>? {
        val end = end(route) ?: return null
        val lat = end.latitude + pan.northMetres / METRES_PER_DEGREE_LAT
        val lon = end.longitude + pan.eastMetres / (METRES_PER_DEGREE_LAT * cos(end.latitude * PI / 180))
        return lat to lon
    }

    /** Every segment as pixel points, the centre at the middle of the view, north up. */
    fun project(route: Route, widthPx: Float, heightPx: Float, pan: Pan): List<List<Px>> {
        val (cLat, cLon) = centre(route, pan) ?: return emptyList()
        val mpp = metresPerPixel(widthPx, heightPx)
        val metresPerDegLon = METRES_PER_DEGREE_LAT * cos(cLat * PI / 180)
        return route.segments.map { segment ->
            segment.map { p ->
                Px(
                    x = (widthPx / 2 + (p.longitude - cLon) * metresPerDegLon / mpp).toFloat(),
                    y = (heightPx / 2 - (p.latitude - cLat) * METRES_PER_DEGREE_LAT / mpp).toFloat(),
                )
            }
        }
    }

    /** A finger drag of (dx, dy) pixels moves the camera the other way: drag right = look west. */
    fun dragToPan(dxPx: Float, dyPx: Float, widthPx: Float, heightPx: Float): Pan {
        val mpp = metresPerPixel(widthPx, heightPx)
        return Pan(eastMetres = -dxPx * mpp, northMetres = dyPx * mpp)
    }
}
