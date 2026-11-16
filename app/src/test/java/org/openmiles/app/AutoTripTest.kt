package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.AutoTripMonitor
import org.openmiles.app.domain.AutoTripMonitor.Decision
import org.openmiles.app.domain.Draft

class AutoTripTest {
    private val start = 1_000_000_000L
    private fun min(m: Double) = start + (m * 60_000).toLong()

    // 0.001 degrees of latitude is about 111 meters.
    @Test
    fun drivingThenParkedEndsWhenTheCarStopped() {
        val m = AutoTripMonitor(start)
        m.onFix(40.000, -75.0, min(0.0), 15f)
        m.onFix(40.010, -75.0, min(1.0), 18f)
        m.onFix(40.020, -75.0, min(2.0), 0f)
        // Sits within a few meters for a while.
        m.onFix(40.02001, -75.0, min(4.0), 0f)
        assertEquals(Decision.KeepGoing, m.check(min(6.9)))
        assertEquals(Decision.Parked(min(2.0)), m.check(min(7.0)))
    }

    @Test
    fun aWalkIsNotADrive() {
        val m = AutoTripMonitor(start)
        // About 1.4 m/s, walking pace, moving steadily so it never looks parked.
        for (i in 0..20) m.onFix(40.0 + i * 0.0004, -75.0, min(i * 0.5), 1.4f)
        assertEquals(Decision.KeepGoing, m.check(min(9.9)))
        assertEquals(Decision.NotADrive, m.check(min(10.0)))
        assertTrue(m.maxSpeedMps < AutoTripMonitor.DRIVE_SPEED_MPS)
    }

    @Test
    fun speedIsWorkedOutWhenThePhoneDoesNotReportIt() {
        val m = AutoTripMonitor(start)
        m.onFix(40.000, -75.0, min(0.0), null)
        m.onFix(40.002, -75.0, min(0.25), null) // about 222 m in 15 s, about 15 m/s
        assertEquals(14.8, m.maxSpeedMps, 0.5)
    }

    @Test
    fun gpsWobbleAndNonsenseSpeedsAreIgnored() {
        val m = AutoTripMonitor(start)
        m.onFix(40.0, -75.0, start, null)
        m.onFix(40.0003, -75.0, start + 1_000, null) // 33 m in 1 s: too short a gap to trust
        m.onFix(40.0003, -75.0, start + 2_000, Float.NaN)
        m.onFix(40.0003, -75.0, start + 3_000, 500f)
        m.onFix(40.0003, -75.0, start + 4_000, -3f)
        assertEquals(0.0, m.maxSpeedMps, 1e-9)
    }

    @Test
    fun noGpsAtAllEndsAsNotADrive() {
        val m = AutoTripMonitor(start)
        assertEquals(Decision.Parked(start), m.check(min(5.0)))
        assertEquals(Decision.NotADrive, m.check(min(10.0)))
    }

    @Test
    fun autoDrivesMustBeLongAndFastEnough() {
        val base = Draft(start, min(10.0), 5_000.0, "", auto = true, maxSpeedMps = 20.0)
        assertTrue(base.worthKeeping())
        assertFalse(base.copy(meters = 300.0).worthKeeping())
        assertFalse(base.copy(maxSpeedMps = 2.0).worthKeeping())
        // Manual drives keep the old, looser rule.
        assertTrue(base.copy(auto = false, meters = 60.0, maxSpeedMps = 0.0).worthKeeping())
    }

    @Test
    fun draftSurvivesSavingAndLoading() {
        val d = Draft(start, min(12.0), 8_123.45, "40.00000,-75.00000,1;40.01000,-75.00000,2", true, 22.5)
        assertEquals(d, Draft.decode(d.encode()))
    }

    @Test
    fun damagedDraftsAreRejected() {
        val good = Draft(start, min(12.0), 100.0, "x", false, 0.0).encode()
        assertNull(Draft.decode(""))
        assertNull(Draft.decode("garbage"))
        assertNull(Draft.decode(good.replace("v1", "v9")))
        assertNull(Draft.decode(good.substring(0, good.length / 2)))
        assertNull(Draft.decode(Draft(start, start - 1, 1.0, "", false, 0.0).encode()))
        assertNull(Draft.decode(Draft(start, start, Double.NaN, "", false, 0.0).encode()))
        assertNull(Draft.decode(Draft(start, start, -5.0, "", false, 0.0).encode()))
        assertNull(Draft.decode(good.replace("\nfalse\n", "\nmaybe\n")))
    }
}
