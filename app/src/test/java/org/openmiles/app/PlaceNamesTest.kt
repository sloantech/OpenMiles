package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.PlaceNames
import org.openmiles.app.domain.PlaceNames.LatLng
import org.openmiles.app.domain.PlaceRule
import org.openmiles.app.domain.RouteMap
import org.openmiles.app.domain.TripEdit

class PlaceNamesTest {
    private val home = LatLng(40.40000, -75.60000)
    private val bank = LatLng(40.42000, -75.58000) // about 2.8 km away
    private val gym = LatLng(40.38000, -75.63000)

    private fun drive(id: Long, from: LatLng, to: LatLng) =
        PlaceNames.Ends(id, id * 3_600_000L, from, to)

    private fun rule(name: String, at: LatLng) = PlaceRule(1, name, at.lat, at.lng, 150.0, null, false)

    /** A few meters of GPS wobble, so visits never land on the exact same point. */
    private fun jitter(p: LatLng, n: Int) = LatLng(p.lat + n * 0.00012, p.lng - n * 0.00010)

    @Test
    fun endsFromRouteEdges() {
        val route = TripEdit.formatRoute(
            listOf(
                TripEdit.RoutePoint(40.1, -75.1, 1000),
                TripEdit.RoutePoint(40.2, -75.2, 2000),
                TripEdit.RoutePoint(40.3, -75.3, 3000)
            )
        )
        val head = route.take(PlaceNames.END_CHARS)
        val tail = route.takeLast(PlaceNames.END_CHARS)
        val (start, end) = PlaceNames.endsFrom(head, tail)!!
        assertEquals(40.1, start.lat, 1e-9)
        assertEquals(-75.3, end.lng, 1e-9)
    }

    @Test
    fun endsFromCutPointsAreSkipped() {
        // The tail starts in the middle of a point; the head ends in the middle of one.
        val head = "40.10000,-75.10000,1000;40.2000"
        val tail = "0,-75.20000,2000;40.30000,-75.30000,3000"
        val (start, end) = PlaceNames.endsFrom(head, tail)!!
        assertEquals(40.1, start.lat, 1e-9)
        assertEquals(40.3, end.lat, 1e-9)
        assertNull(PlaceNames.endsFrom("", ""))
        assertNull(PlaceNames.endsFrom("junk", "junk"))
    }

    @Test
    fun singlePointRoute() {
        val route = "40.10000,-75.10000,1000"
        val (start, end) = PlaceNames.endsFrom(route, route)!!
        assertEquals(start, end)
    }

    @Test
    fun threeVisitsMakeASpot() {
        val drives = listOf(
            drive(1, jitter(home, 0), jitter(bank, 0)),
            drive(2, jitter(bank, 1), jitter(home, 1)),
            drive(3, jitter(home, 2), jitter(gym, 0))
        )
        val spots = PlaceNames.unnamedSpots(drives, emptyList(), emptyList())
        assertEquals(1, spots.size) // home: 3 visits; bank: 2; gym: 1
        assertEquals(3, spots[0].visits)
        assertEquals(home.lat, spots[0].center.lat, 0.001)
        assertEquals(3L, spots[0].lastTripId)
        assertEquals(false, spots[0].lastWasEnd)
    }

    @Test
    fun busiestSpotFirst() {
        val drives = (1L..6L).map { i ->
            if (i % 2 == 0L) drive(i, jitter(home, i.toInt()), jitter(bank, i.toInt())) else drive(i, jitter(bank, i.toInt()), jitter(home, i.toInt()))
        } + listOf(drive(7, jitter(home, 7), jitter(gym, 0)))
        val spots = PlaceNames.unnamedSpots(drives, emptyList(), emptyList())
        assertEquals(2, spots.size)
        assertEquals(7, spots[0].visits) // home
        assertEquals(6, spots[1].visits) // bank
    }

    @Test
    fun namedPlacesAreNotSuggested() {
        val drives = (1L..4L).map { drive(it, jitter(home, it.toInt()), jitter(bank, it.toInt())) }
        val spots = PlaceNames.unnamedSpots(drives, listOf(rule("Home", home)), emptyList())
        assertEquals(1, spots.size)
        assertEquals(bank.lat, spots[0].center.lat, 0.001)
    }

    @Test
    fun dismissedSpotsStayQuiet() {
        val drives = (1L..4L).map { drive(it, jitter(home, it.toInt()), jitter(bank, it.toInt())) }
        val spots = PlaceNames.unnamedSpots(drives, emptyList(), listOf(home))
        assertEquals(1, spots.size)
        assertEquals(bank.lat, spots[0].center.lat, 0.001)
    }

    @Test
    fun roundTripCountsOnce() {
        // Three loops around the block from home: one visit each, not two.
        val drives = (1L..3L).map { drive(it, jitter(home, 0), jitter(home, 1)) }
        val spots = PlaceNames.unnamedSpots(drives, emptyList(), emptyList())
        assertEquals(3, spots.single().visits)
    }

    @Test
    fun farApartIsNotTheSameSpot() {
        val near = LatLng(home.lat + 0.004, home.lng) // about 440 m north
        // Each drive goes somewhere different, so only the two starting spots repeat.
        val drives = (1L..3L).map { drive(it, jitter(home, it.toInt()), LatLng(41.0 + it, -75.0)) } +
            (4L..6L).map { drive(it, jitter(near, it.toInt()), LatLng(42.0 + it, -75.0)) }
        val spots = PlaceNames.unnamedSpots(drives, emptyList(), emptyList())
        assertEquals(2, spots.size)
        assertTrue(spots.all { it.visits == 3 })
    }

    @Test
    fun badPointsAreIgnored() {
        val bad = LatLng(Double.NaN, -75.0)
        val drives = (1L..3L).map { drive(it, bad, bad) }
        assertTrue(PlaceNames.unnamedSpots(drives, emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("Home to Bank", PlaceNames.label("Home", "Bank"))
        assertEquals("From Home", PlaceNames.label("Home", null))
        assertEquals("To Bank", PlaceNames.label(null, "Bank"))
        assertNull(PlaceNames.label(null, null))
        assertEquals("Home", PlaceNames.nameAt(listOf(rule("Home", home)), jitter(home, 1)))
        assertNull(PlaceNames.nameAt(listOf(rule("Home", home)), bank))
        assertNull(PlaceNames.nameAt(listOf(rule("Home", home)), null))
    }

    @Test
    fun dismissedRoundTrip() {
        val list = listOf(home, bank)
        val back = PlaceNames.decodeDismissed(PlaceNames.encodeDismissed(list))
        assertEquals(2, back.size)
        assertEquals(home.lat, back[0].lat, 1e-5)
        assertTrue(PlaceNames.decodeDismissed("junk;1,2,3;95,0;40,-75").size == 1)
        assertTrue(PlaceNames.decodeDismissed(null).isEmpty())
        val many = (0 until 300).map { LatLng(40.0 + it * 0.01, -75.0) }
        assertEquals(PlaceNames.MAX_DISMISSED, PlaceNames.decodeDismissed(PlaceNames.encodeDismissed(many)).size)
    }

    @Test
    fun presetsAreValid() {
        val names = PlaceNames.PRESETS.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue("Home" in names)
        PlaceNames.PRESETS.mapNotNull { it.kind }.forEach { assertTrue(it in listOf("BUSINESS", "PERSONAL", "MEDICAL", "CHARITY")) }
    }

    @Test
    fun spotLink() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=40.40000%2C-75.60000",
            RouteMap.spotLink(40.4, -75.6)
        )
        assertNull(RouteMap.spotLink(Double.NaN, 0.0))
        assertNull(RouteMap.spotLink(91.0, 0.0))
    }
}
