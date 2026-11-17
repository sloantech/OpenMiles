package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openmiles.app.data.Source
import org.openmiles.app.domain.TripEdit

class TripEditTest {
    // Four points heading north, 0.01 degrees (about 1.11 km) apart, one minute apart.
    private val route = "40.00000,-75.00000,0;40.01000,-75.00000,60000;40.02000,-75.00000,120000;40.03000,-75.00000,180000"

    @Test
    fun splitFollowsTheRoute() {
        // Split exactly on the second point: one third of the route is before it.
        val parts = TripEdit.split(0, 180_000, 3_000.0, route, 60_000)!!
        assertEquals(1_000.0, parts.firstMeters, 1.0)
        assertEquals(2_000.0, parts.secondMeters, 1.0)
        assertEquals(2, TripEdit.parseRoute(parts.firstRoute).size)
        assertEquals(2, TripEdit.parseRoute(parts.secondRoute).size)
    }

    @Test
    fun splitBetweenPointsShareTheStep() {
        val parts = TripEdit.split(0, 180_000, 3_000.0, route, 90_000)!!
        assertEquals(1_500.0, parts.firstMeters, 1.0)
    }

    @Test
    fun splitWithoutRouteUsesTime() {
        val parts = TripEdit.split(0, 1_000_000, 10_000.0, "", 250_000)!!
        assertEquals(2_500.0, parts.firstMeters, 1e-6)
        assertEquals(7_500.0, parts.secondMeters, 1e-6)
    }

    @Test
    fun splitMustBeInsideTheDrive() {
        assertNull(TripEdit.split(1_000, 2_000, 5.0, "", 1_000))
        assertNull(TripEdit.split(1_000, 2_000, 5.0, "", 2_000))
        assertNull(TripEdit.split(1_000, 2_000, 5.0, "", 50))
    }

    @Test
    fun milesNeverGoMissingWhenSplitting() {
        val parts = TripEdit.split(0, 180_000, 4_321.0, route, 133_000)!!
        assertEquals(4_321.0, parts.firstMeters + parts.secondMeters, 1e-6)
    }

    @Test
    fun mergeHelpers() {
        assertEquals("a;b", TripEdit.mergeRoutes("a", "b"))
        assertEquals("b", TripEdit.mergeRoutes("", "b"))
        assertEquals("Client visit; Lunch", TripEdit.mergeNotes(" Client visit ", "Lunch"))
        assertEquals("Same", TripEdit.mergeNotes("Same", "Same"))
        assertEquals("", TripEdit.mergeNotes("", " "))
    }

    @Test
    fun endBeforeStartMeansNextDay() {
        val day = 1_000_000_000L
        val next = day + 86_400_000L
        assertEquals((day + 600 * 60_000L) to (day + 630 * 60_000L), TripEdit.times(day, 600, 630, next))
        assertEquals((day + 1430 * 60_000L) to (next + 10 * 60_000L), TripEdit.times(day, 1430, 10, next))
        assertNull(TripEdit.times(day, 600, 600, next))
    }

    @Test
    fun exportLabelSaysWhenEdited() {
        assertEquals("GPS", Source.exportLabel(Source.GPS, false))
        assertEquals("GPS, edited", Source.exportLabel(Source.GPS, true))
        assertEquals("Google Timeline, edited", Source.exportLabel(Source.TIMELINE, true))
        assertEquals("Added by hand", Source.exportLabel(Source.MANUAL, true))
    }
}
