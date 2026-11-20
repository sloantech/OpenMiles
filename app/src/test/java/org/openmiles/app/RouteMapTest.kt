package org.openmiles.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.RouteMap
import org.openmiles.app.domain.TripEdit

class RouteMapTest {
    private fun p(lat: Double, lng: Double, t: Long) = TripEdit.RoutePoint(lat, lng, t)

    // A drive heading north then east, about 1.1 km each way.
    private val drive = listOf(p(40.0, -75.0, 0), p(40.01, -75.0, 60_000), p(40.01, -74.987, 120_000))

    @Test
    fun pointsAreSortedByTime() {
        val pts = RouteMap.points("40.01000,-75.00000,60000;40.00000,-75.00000,0;bad;99.0,0.0,5")
        assertEquals(2, pts.size)
        assertEquals(0L, pts.first().timeMs)
    }

    @Test
    fun projectStaysInsideTheBox() {
        val out = RouteMap.project(drive, 300f, 200f, 10f)
        assertEquals(3, out.size)
        out.forEach {
            assertTrue(it.x in 10f..290f)
            assertTrue(it.y in 10f..190f)
        }
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val out = RouteMap.project(drive, 300f, 300f, 10f)
        assertTrue("going north moves up the screen", out[1].y < out[0].y)
        assertTrue("going east moves right", out[2].x > out[1].x)
        assertEquals(out[0].x, out[1].x, 0.01f)
    }

    @Test
    fun shapeIsNotStretched() {
        // At latitude 40, 0.013 degrees of longitude is about the same distance as 0.01 of latitude.
        val out = RouteMap.project(drive, 400f, 400f, 0f)
        val up = out[0].y - out[1].y
        val right = out[2].x - out[1].x
        assertEquals(1.0, (right / up).toDouble(), 0.05)
    }

    @Test
    fun parkedCarStaysSmall() {
        val wobble = listOf(p(40.0, -75.0, 0), p(40.00001, -75.00001, 1000))
        val out = RouteMap.project(wobble, 300f, 300f, 10f)
        assertTrue(kotlin.math.abs(out[0].x - out[1].x) < 5f)
        assertTrue(kotlin.math.abs(out[0].y - out[1].y) < 5f)
    }

    @Test
    fun singlePointIsCentered() {
        val out = RouteMap.project(listOf(p(40.0, -75.0, 0)), 300f, 200f, 10f)
        assertEquals(150f, out[0].x, 0.01f)
        assertEquals(100f, out[0].y, 0.01f)
    }

    @Test
    fun emptyOrZeroSizeGivesNothing() {
        assertTrue(RouteMap.project(emptyList(), 300f, 200f, 10f).isEmpty())
        assertTrue(RouteMap.project(drive, 0f, 200f, 10f).isEmpty())
    }

    @Test
    fun thinKeepsEndsAndLimit() {
        val many = (0 until 5000).map { p(40.0 + it * 0.0001, -75.0, it.toLong()) }
        val thin = RouteMap.thin(many, RouteMap.MAX_DRAW_POINTS)
        assertEquals(RouteMap.MAX_DRAW_POINTS, thin.size)
        assertEquals(many.first(), thin.first())
        assertEquals(many.last(), thin.last())
        assertEquals(drive, RouteMap.thin(drive, 600))
    }

    @Test
    fun widthOfDrive() {
        assertEquals(1_112.0, RouteMap.widthMeters(drive), 20.0)
        assertEquals(0.0, RouteMap.widthMeters(drive.take(1)), 0.0)
    }

    @Test
    fun mapsLinkFormat() {
        val link = RouteMap.mapsLink(drive)!!
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=40.00000%2C-75.00000" +
                "&destination=40.01000%2C-74.98700&waypoints=40.01000%2C-75.00000&travelmode=driving",
            link
        )
        assertFalse("never starts navigation", link.contains("dir_action"))
    }

    @Test
    fun mapsLinkLimitsStops() {
        val many = (0 until 1000).map { p(40.0 + it * 0.0001, -75.0, it.toLong()) }
        val link = RouteMap.mapsLink(many)!!
        val stops = link.substringAfter("&waypoints=").substringBefore("&").split("%7C")
        assertEquals(RouteMap.MAX_WAYPOINTS, stops.size)
        assertTrue(link.startsWith("https://www.google.com/maps/dir/?api=1&origin=40.00000%2C-75.00000"))
        assertTrue(link.contains("&destination=40.09990%2C-75.00000"))
    }

    @Test
    fun mapsLinkTwoPointsHasNoStops() {
        val link = RouteMap.mapsLink(drive.take(2))!!
        assertFalse(link.contains("waypoints"))
    }

    @Test
    fun mapsLinkNoRoute() {
        assertNull(RouteMap.mapsLink(emptyList()))
    }

    @Test
    fun mapsLinkIgnoresPhoneLanguage() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // uses a comma for decimals
            assertTrue(RouteMap.mapsLink(drive)!!.contains("40.00000%2C-75.00000"))
        } finally {
            Locale.setDefault(old)
        }
    }
}
