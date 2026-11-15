package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.Geo
import org.openmiles.app.domain.TripRecorder
import org.openmiles.app.domain.TripRecorder.Fix

class TripRecorderTest {
    // 0.0001 degrees of latitude is about 11.12 meters.
    private fun fix(step: Int, seconds: Int = step * 5, accuracy: Float? = 5f, lngOffset: Double = 0.0) =
        Fix(
            lat = 40.0 + step * 0.0001,
            lng = -75.0 + lngOffset,
            timeMs = 1_000_000L + seconds * 1000L,
            accuracyM = accuracy
        )

    @Test
    fun oneDegreeOfLatitudeIsAbout111Kilometers() {
        val d = Geo.distanceMeters(40.0, -75.0, 41.0, -75.0)
        assertEquals(111_195.0, d, 100.0)
    }

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, Geo.distanceMeters(40.0, -75.0, 40.0, -75.0), 1e-9)
    }

    @Test
    fun addsUpASteadyDrive() {
        val r = TripRecorder()
        (0..9).forEach { assertTrue(r.add(fix(it))) }
        // 9 steps of about 11.12 m
        assertEquals(100.07, r.meters, 0.5)
        assertEquals(10, r.pointCount)
    }

    @Test
    fun ignoresGpsWobbleWhileParked() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0)))
        // Moves of about 3 m, under the 10 m minimum
        listOf(0.00003, -0.00002, 0.00004).forEachIndexed { i, off ->
            val f = Fix(40.0 + off, -75.0, 1_000_000L + (i + 1) * 5000L, 5f)
            assertFalse(r.add(f))
        }
        assertEquals(0.0, r.meters, 1e-9)
    }

    @Test
    fun slowCreepEventuallyCounts() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0)))
        // 6 m at a time. The first is skipped, the second is 12 m from the last accepted point.
        assertFalse(r.add(Fix(40.000054, -75.0, 1_005_000L, 5f)))
        assertTrue(r.add(Fix(40.000108, -75.0, 1_010_000L, 5f)))
        assertEquals(12.0, r.meters, 0.5)
    }

    @Test
    fun ignoresInaccurateFixes() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0)))
        assertFalse(r.add(fix(1, accuracy = 80f)))
        assertEquals(0.0, r.meters, 1e-9)
        assertTrue(r.add(fix(2, accuracy = null)))
    }

    @Test
    fun ignoresImpossibleJumps() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0)))
        // One full degree north (111 km) in 5 seconds
        val glitch = Fix(41.0, -75.0, 1_005_000L, 5f)
        assertFalse(r.add(glitch))
        assertEquals(0.0, r.meters, 1e-9)
        // The next real fix is still measured from the last good one
        assertTrue(r.add(fix(1)))
        assertEquals(11.12, r.meters, 0.2)
    }

    @Test
    fun ignoresRepeatedOrOutOfOrderTimestamps() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0, seconds = 10)))
        assertFalse(r.add(fix(1, seconds = 10)))
        assertFalse(r.add(fix(2, seconds = 5)))
        assertEquals(0.0, r.meters, 1e-9)
    }

    @Test
    fun highwaySpeedIsAccepted() {
        val r = TripRecorder()
        assertTrue(r.add(fix(0)))
        // 0.0015 degrees (about 167 m) in 5 seconds is about 75 mph
        val f = Fix(40.0015, -75.0, 1_005_000L, 5f)
        assertTrue(r.add(f))
        assertEquals(166.8, r.meters, 1.0)
    }

    @Test
    fun resetClearsEverything() {
        val r = TripRecorder()
        (0..3).forEach { r.add(fix(it)) }
        r.reset()
        assertEquals(0.0, r.meters, 0.0)
        assertEquals(0, r.pointCount)
        assertEquals("", r.routeString())
    }

    @Test
    fun routeStringHasOnePointPerAcceptedFix() {
        val r = TripRecorder()
        (0..2).forEach { r.add(fix(it)) }
        val parts = r.routeString().split(";")
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("40.00000,-75.00000,"))
    }
}
