package com.jakubfronczyk.northform.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.UIKitView
import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import com.jakubfronczyk.northform.ui.Theme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreLocation.CLLocationCoordinate2D
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKOverlayProtocol
import platform.MapKit.MKOverlayRenderer
import platform.MapKit.MKPolyline
import platform.MapKit.MKPolylineRenderer
import platform.MapKit.MKUserTrackingModeFollow
import platform.MapKit.addOverlay
import platform.MapKit.removeOverlay
import platform.UIKit.UIColor
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
    val drawn = remember { DrawnRoute() }
    UIKitView(
        factory = {
            MKMapView().apply {
                delegate = drawn.renderer
                this.showsUserLocation = showsUserLocation
                if (showsUserLocation) setUserTrackingMode(MKUserTrackingModeFollow, animated = false)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { map -> drawn.sync(map, route, followEnd = !showsUserLocation) },
    )
}

/** What the map currently shows, one polyline per route segment, so an update redraws only the segment that grew. */
@OptIn(ExperimentalForeignApi::class)
private class DrawnRoute {
    val renderer = RouteRenderer()
    private var route: Route? = null
    private val lines = ArrayList<MKPolyline?>()

    fun sync(map: MKMapView, next: Route, followEnd: Boolean) {
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
        if (followEnd && last != null && last != route?.segments?.lastOrNull()?.lastOrNull()) {
            map.setRegion(MKCoordinateRegionMakeWithDistance(CLLocationCoordinate2DMake(last.latitude, last.longitude), 900.0, 900.0), animated = true)
        }
        route = next
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

/** `rendererFor:` — the one delegate method: the accent-coloured 5 pt line. */
private class RouteRenderer : NSObject(), MKMapViewDelegateProtocol {
    private val accent = Theme.accent.toArgb().let { argb ->
        UIColor(red = ((argb shr 16) and 0xFF) / 255.0, green = ((argb shr 8) and 0xFF) / 255.0, blue = (argb and 0xFF) / 255.0, alpha = 1.0)
    }

    override fun mapView(mapView: MKMapView, rendererForOverlay: MKOverlayProtocol): MKOverlayRenderer =
        MKPolylineRenderer(overlay = rendererForOverlay).apply {
            strokeColor = accent
            lineWidth = 5.0
        }
}
