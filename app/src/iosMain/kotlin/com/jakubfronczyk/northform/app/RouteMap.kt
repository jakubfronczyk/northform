package com.jakubfronczyk.northform.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.UIKitView
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import com.jakubfronczyk.northform.ui.theme.Theme
import com.jakubfronczyk.northform.ui.run.LocalMapCamera
import com.jakubfronczyk.northform.ui.run.Pan
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapRectUnion
import platform.MapKit.MKMapView
import platform.UIKit.UIEdgeInsetsMake
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.MapKit.MKPolyline
import platform.MapKit.MKPolylineRenderer
import platform.MapKit.MKUserTrackingModeFollow
import platform.MapKit.addOverlay
import platform.MapKit.removeOverlay
import platform.UIKit.UIColor
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * The route on Apple Maps (`Features/Run/LiveRunView.swift:121-157`), D13: an `MKMapView` placed in
 * Compose with `UIKitView`. Only a changed route touches MapKit, and only its last segment is redrawn,
 * so the 1 Hz snapshots cost nothing here. With real GPS it shows the system "you are here" dot and
 * follows it (D104); in replay the camera follows the route's end. The line shows only what was counted (D102).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun RouteMap(route: Route, showsUserLocation: Boolean) {
    // run-flow-ui/06: the one camera the run flow shares. A finger on the map flips `following` off (the re-center
    // button appears); `recenter()` flips it back and the map follows again. MapKit keeps the panned position itself,
    // so the camera's `pan` offset stays zero here — it only matters to the Compose canvas.
    val camera = LocalMapCamera.current
    val following = camera.following
    val drawn = remember { DrawnRoute(onUserDrag = { if (camera.following) camera.dragBy(Pan()) }) }
    UIKitView(
        factory = {
            MKMapView().apply {
                delegate = drawn.renderer
                this.showsUserLocation = showsUserLocation
                if (showsUserLocation) setUserTrackingMode(MKUserTrackingModeFollow, animated = false)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { map -> drawn.sync(map, route, follow = following, followUser = showsUserLocation, fit = camera.fitted) },
    )
}

/** What the map currently shows, one polyline per route segment, so an update redraws only the segment that grew. */
@OptIn(ExperimentalForeignApi::class)
private class DrawnRoute(onUserDrag: () -> Unit) {
    val renderer = RouteRenderer(onUserDrag)
    private var route: Route? = null
    private var following = true
    private val lines = ArrayList<MKPolyline?>()

    /** The fitted camera's one-time framing is done (summary-screen/04). */
    private var fitDone = false

    fun sync(map: MKMapView, next: Route, follow: Boolean, followUser: Boolean, fit: Boolean = false) {
        if (follow && !following && !fit) { // "Re-center" tapped: back onto the dot (live) or the route's end (replay)
            if (followUser) map.setUserTrackingMode(MKUserTrackingModeFollow, animated = true)
            else route?.let { center(map, it, animated = true) }
        }
        following = follow
        if (next === route) return
        val segments = next.segments
        while (lines.size > segments.size) lines.removeLast()?.let { map.removeOverlay(it) }
        if (lines.isNotEmpty()) lines.removeLast()?.let { map.removeOverlay(it) } // the last segment may have grown
        for (i in lines.size until segments.size) {
            val line = polyline(segments[i])
            line?.let { map.addOverlay(it) }
            lines += line
        }
        val last = segments.lastOrNull()?.lastOrNull()
        if (fit) {
            // summary-screen/04: the whole route in view once; after that the user pans and zooms freely.
            if (!fitDone) { fitAll(map); fitDone = true }
        } else if (follow && !followUser && last != null && last != route?.segments?.lastOrNull()?.lastOrNull()) center(map, next, animated = true)
        route = next
    }

    private fun fitAll(map: MKMapView) {
        val drawnLines = lines.filterNotNull()
        if (drawnLines.isEmpty()) return
        var rect = drawnLines.first().boundingMapRect
        for (line in drawnLines.drop(1)) rect = MKMapRectUnion(rect, line.boundingMapRect)
        map.setVisibleMapRect(rect, edgePadding = UIEdgeInsetsMake(32.0, 32.0, 32.0, 32.0), animated = false)
    }

    private fun center(map: MKMapView, route: Route, animated: Boolean) {
        val last = route.segments.lastOrNull()?.lastOrNull() ?: return
        map.setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(last.latitude, last.longitude), 900.0, 900.0), animated = animated)
    }

    private fun polyline(segment: List<RoutePoint>): MKPolyline? {
        if (segment.size < 2) return null
        return memScoped {
            val coords = allocArray<CLLocationCoordinate2D>(segment.size)
            segment.forEachIndexed { i, p ->
                coords[i].latitude = p.latitude
                coords[i].longitude = p.longitude
            }
            MKPolyline.polylineWithCoordinates(coords, segment.size.toULong())
        }
    }
}

/**
 * The delegate: `rendererFor:` draws the accent-coloured 5 pt line; `regionWillChangeAnimated:` tells a user drag from a
 * programmatic move by whether one of the map's own gesture recognisers is live (the UIKit idiom; MapKit has no "user
 * panned" callback). A live gesture also drops MapKit's own tracking mode, which is why the camera must learn of it.
 */
private class RouteRenderer(private val onUserDrag: () -> Unit) : NSObject(), MKMapViewDelegateProtocol {
    private val accent = Theme.accent.toArgb().let { argb ->
        UIColor(red = ((argb shr 16) and 0xFF) / 255.0, green = ((argb shr 8) and 0xFF) / 255.0, blue = (argb and 0xFF) / 255.0, alpha = 1.0)
    }

    override fun mapView(mapView: MKMapView, rendererForOverlay: MKOverlayProtocol): MKOverlayRenderer =
        MKPolylineRenderer(overlay = rendererForOverlay).apply {
            strokeColor = accent
            lineWidth = 5.0
        }

    override fun mapView(mapView: MKMapView, regionWillChangeAnimated: Boolean) {
        val gestures = (mapView.subviews.firstOrNull() as? UIView)?.gestureRecognizers.orEmpty()
        val userMoving = gestures.any { (it as UIGestureRecognizer).state.let { s -> s == UIGestureRecognizerStateBegan || s == UIGestureRecognizerStateChanged } }
        if (userMoving) onUserDrag()
    }
}
