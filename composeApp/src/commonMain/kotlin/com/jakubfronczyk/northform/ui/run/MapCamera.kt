package com.jakubfronczyk.northform.ui.run

import androidx.compose.runtime.staticCompositionLocalOf
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
class MapCamera(
    /** The Summary's camera (summary-screen/04): the whole route in view once, then free pan/zoom; never follows, no re-center. */
    val fitted: Boolean = false,
) {
    var following by mutableStateOf(!fitted)
        private set
    var pan by mutableStateOf(Pan())
        private set

    /** A finger moved the map: stop following, keep the offset. */
    fun dragBy(delta: Pan) {
        following = false
        pan += delta
    }

    fun recenter() {
        if (fitted) return
        following = true
        pan = Pan()
    }
}

/**
 * The one camera the run flow shares with whatever draws the map. A `CompositionLocal` rather than a
 * parameter so the platform map (`:app/RouteMap.kt`, slot type `(Route) -> Unit`) can adopt it later
 * without a signature change. `RunFlow` provides it; there is no default, because a silently created
 * camera would not be the one the re-center button reads. Static: the provided camera never changes,
 * its fields are snapshot state.
 */
val LocalMapCamera = staticCompositionLocalOf<MapCamera> { error("no MapCamera provided: build the map inside RunFlow") }

/**
 * Lat/lon → canvas pixels, pure (table-tested): an equirectangular projection to metres around the
 * route end ([metres]), then the camera centre (route end + pan) at the middle of the view ([toPx]). Fine at run scale — a 900 m window, the same as `RouteMap.kt`'s
 * `MKCoordinateRegionMakeWithDistance`. Mercator would change nothing visible over 1 km.
 */
object RouteProjection {
    /** Metres across the shorter side of the view. */
    const val SPAN_METRES = 900.0
    private const val METRES_PER_DEGREE_LAT = 111_320.0

    data class Px(val x: Float, val y: Float)

    /** Metres per pixel at this view size. */
    fun metresPerPixel(widthPx: Float, heightPx: Float): Double = SPAN_METRES / min(widthPx, heightPx).coerceAtLeast(1f)

    /**
     * A fitted camera's window (summary-screen/04): the route's extent plus a margin on each side, never under
     * [SPAN_METRES] / 3 so a short run is not a dot. The centre is the extent's middle, as a [Pan] from the route end.
     */
    data class Fit(val spanMetres: Double, val centre: Pan)

    fun fit(metres: List<List<Pan>>, margin: Double = 1.3): Fit {
        val all = metres.flatten()
        if (all.isEmpty()) return Fit(SPAN_METRES, Pan())
        val east = all.minOf { it.eastMetres }..all.maxOf { it.eastMetres }
        val north = all.minOf { it.northMetres }..all.maxOf { it.northMetres }
        val extent = maxOf(east.endInclusive - east.start, north.endInclusive - north.start)
        return Fit((extent * margin).coerceAtLeast(SPAN_METRES / 3), Pan((east.start + east.endInclusive) / 2, (north.start + north.endInclusive) / 2))
    }

    /** The route's last point, or null for a route with nothing drawn yet. */
    fun end(route: Route): RoutePoint? = route.segments.lastOrNull()?.lastOrNull()

    /**
     * Every segment as metres (east, north) from the route's end: the part of the projection that
     * depends on the route alone, so a canvas computes it once per route and pans/zooms with a transform.
     */
    fun metres(route: Route): List<List<Pan>> {
        val end = end(route) ?: return emptyList()
        val metresPerDegLon = METRES_PER_DEGREE_LAT * cos(end.latitude * PI / 180)
        return route.segments.map { segment ->
            segment.map { p ->
                Pan(
                    eastMetres = (p.longitude - end.longitude) * metresPerDegLon,
                    northMetres = (p.latitude - end.latitude) * METRES_PER_DEGREE_LAT,
                )
            }
        }
    }

    /** One point in metres from the route end → pixels: the camera centre (end + pan) at the middle, north up. */
    fun toPx(m: Pan, widthPx: Float, heightPx: Float, pan: Pan): Px {
        val mpp = metresPerPixel(widthPx, heightPx)
        return Px(
            x = (widthPx / 2 + (m.eastMetres - pan.eastMetres) / mpp).toFloat(),
            y = (heightPx / 2 - (m.northMetres - pan.northMetres) / mpp).toFloat(),
        )
    }

    /** Every segment as pixel points, the centre at the middle of the view, north up. */
    fun project(route: Route, widthPx: Float, heightPx: Float, pan: Pan): List<List<Px>> =
        metres(route).map { segment -> segment.map { toPx(it, widthPx, heightPx, pan) } }

    /** A finger drag of (dx, dy) pixels moves the camera the other way: drag right = look west. */
    fun dragToPan(dxPx: Float, dyPx: Float, widthPx: Float, heightPx: Float): Pan {
        val mpp = metresPerPixel(widthPx, heightPx)
        return Pan(eastMetres = -dxPx * mpp, northMetres = dyPx * mpp)
    }
}
