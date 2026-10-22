package com.jakubfronczyk.northform.ui.run

import com.jakubfronczyk.northform.engine.run.Route
import com.jakubfronczyk.northform.engine.run.RoutePoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U5 / Swift run-screen-map/02: the camera follows the end, a drag holds it, re-center snaps back. */
class RouteProjectionTest {

    private val w = 390f
    private val h = 330f
    private val berlin = Route() + RoutePoint(52.5145, 13.3501, startsSegment = true) + RoutePoint(52.5155, 13.3501, startsSegment = false)

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.5f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")

    @Test
    fun following_puts_the_route_end_at_the_centre() {
        val end = RouteProjection.project(berlin, w, h, Pan()).last().last()
        assertNear(w / 2, end.x)
        assertNear(h / 2, end.y)
    }

    @Test
    fun the_span_is_900_m_across_the_shorter_side_and_north_is_up() {
        val pts = RouteProjection.project(berlin, w, h, Pan()).single()
        val start = pts.first()
        val end = pts.last()
        // 0.001° lat ≈ 111.3 m; 900 m over 330 px → 0.3667 px/m → 40.8 px
        assertNear(40.8f, start.y - end.y, 0.6f)
        assertNear(end.x, start.x, 0.01f) // same longitude, same x
    }

    @Test
    fun a_pan_east_moves_the_route_left_by_the_same_metres() {
        val mpp = RouteProjection.metresPerPixel(w, h)
        val end = RouteProjection.project(berlin, w, h, Pan(eastMetres = 100.0)).last().last()
        assertNear(w / 2 - (100.0 / mpp).toFloat(), end.x, 0.6f)
        assertNear(h / 2, end.y)
    }

    @Test
    fun a_drag_right_is_a_pan_west_of_the_dragged_metres() {
        val pan = RouteProjection.dragToPan(dxPx = 30f, dyPx = -15f, widthPx = w, heightPx = h)
        val mpp = RouteProjection.metresPerPixel(w, h)
        assertEquals(-30 * mpp, pan.eastMetres, 1e-9)
        assertEquals(-15 * mpp, pan.northMetres, 1e-9)
    }

    @Test
    fun an_empty_route_projects_nothing() {
        assertEquals(emptyList(), RouteProjection.project(Route(), w, h, Pan()))
    }

    @Test
    fun segments_stay_separate() {
        val gapped = berlin + RoutePoint(52.5165, 13.3521, startsSegment = true) + RoutePoint(52.5166, 13.3522, startsSegment = false)
        assertEquals(listOf(2, 2), RouteProjection.project(gapped, w, h, Pan()).map { it.size })
    }

    @Test
    fun a_drag_stops_following_and_recenter_snaps_back() {
        val camera = MapCamera()
        assertTrue(camera.following)
        camera.dragBy(Pan(eastMetres = 40.0))
        camera.dragBy(Pan(northMetres = -10.0))
        assertFalse(camera.following, "a drag holds the map where the finger left it")
        assertEquals(Pan(40.0, -10.0), camera.pan, "drags add up")
        camera.recenter()
        assertTrue(camera.following)
        assertTrue(camera.pan.isZero)
    }
}

/** summary-screen/04: the fitted camera's window around the whole route. */
class RouteFitTest {
    @Test
    fun the_fit_spans_the_route_with_a_margin_and_centres_on_its_middle() {
        val metres = listOf(listOf(Pan(-100.0, 0.0), Pan(300.0, 200.0)))
        val fit = RouteProjection.fit(metres)
        assertEquals(400.0 * 1.3, fit.spanMetres, 1e-9, "the wider extent (east, 400 m) with the margin")
        assertEquals(Pan(100.0, 100.0), fit.centre)
    }

    @Test
    fun a_short_route_is_not_a_dot() {
        assertEquals(RouteProjection.SPAN_METRES / 3, RouteProjection.fit(listOf(listOf(Pan(0.0, 0.0), Pan(5.0, 5.0)))).spanMetres, 1e-9)
    }
}
