package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.AutoClassify
import org.openmiles.app.domain.PlaceRule
import org.openmiles.app.domain.WorkHours
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AutoClassifyTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 0.001 degrees of latitude is about 111 m.
    private val home = PlaceRule(1, "Home", 40.000, -75.000, 150.0, categoryId = 2, ignoreDrives = true)
    private val office = PlaceRule(2, "Office", 40.100, -75.000, 150.0, categoryId = 1, ignoreDrives = false)
    private val cats = setOf(1L, 2L, 3L)
    private val work = WorkHours(
        enabled = true,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        startMin = 8 * 60, endMin = 17 * 60, categoryId = 3
    )
    private val monday10am = at(2026, 11, 16, 10)
    private val saturday10am = at(2026, 11, 21, 10)

    @Test
    fun destinationWinsOverStart() {
        // Home (category 2) to Office (category 1): the place where it ended wins.
        val c = AutoClassify.categoryFor(40.0005 to -75.0, 40.1003 to -75.0, saturday10am, listOf(home, office), WorkHours(), cats, zone)
        assertEquals(1L, c)
    }

    @Test
    fun startPlaceUsedWhenDestinationHasNoRule() {
        val c = AutoClassify.categoryFor(40.1001 to -75.0, 41.0 to -75.0, saturday10am, listOf(home, office), WorkHours(), cats, zone)
        assertEquals(1L, c)
    }

    @Test
    fun placeRuleBeatsWorkHours() {
        val c = AutoClassify.categoryFor(41.0 to -75.0, 40.0002 to -75.0, monday10am, listOf(home, office), work, cats, zone)
        assertEquals(2L, c)
    }

    @Test
    fun workHoursApplyOnlyOnWorkDaysAndHours() {
        assertEquals(3L, AutoClassify.categoryFor(null, null, monday10am, emptyList(), work, cats, zone))
        assertNull(AutoClassify.categoryFor(null, null, saturday10am, emptyList(), work, cats, zone))
        assertNull(AutoClassify.categoryFor(null, null, at(2026, 11, 16, 17, 0), emptyList(), work, cats, zone))
        assertEquals(3L, AutoClassify.categoryFor(null, null, at(2026, 11, 16, 8, 0), emptyList(), work, cats, zone))
        assertNull(AutoClassify.categoryFor(null, null, monday10am, emptyList(), work.copy(enabled = false), cats, zone))
    }

    @Test
    fun overnightShiftCountsTheEarlyMorningForTheDayBefore() {
        val night = work.copy(startMin = 22 * 60, endMin = 6 * 60, days = setOf(DayOfWeek.FRIDAY))
        assertTrue(night.covers(at(2026, 11, 20, 23), zone)) // Friday 11 PM
        assertTrue(night.covers(at(2026, 11, 21, 5), zone)) // Saturday 5 AM, still Friday's shift
        assertFalse(night.covers(at(2026, 11, 22, 5), zone)) // Sunday 5 AM
    }

    @Test
    fun rulesForDeletedCategoriesAreIgnored() {
        val c = AutoClassify.categoryFor(null, 40.1 to -75.0, monday10am, listOf(office), work, setOf(2L), zone)
        assertNull(c)
    }

    @Test
    fun nothingMatchesNothingChanges() {
        assertNull(AutoClassify.categoryFor(45.0 to -80.0, 45.1 to -80.0, saturday10am, listOf(home, office), WorkHours(), cats, zone))
    }

    @Test
    fun ignoredOnlyWhenBothEndsAreInsideTheSamePlace() {
        assertTrue(AutoClassify.ignored(40.0001 to -75.0, 40.0006 to -75.0, listOf(home, office)))
        assertFalse(AutoClassify.ignored(40.0001 to -75.0, 40.1 to -75.0, listOf(home, office)))
        assertFalse(AutoClassify.ignored(40.1 to -75.0, 40.1005 to -75.0, listOf(home, office))) // office not ignored
        assertFalse(AutoClassify.ignored(null, 40.0 to -75.0, listOf(home)))
    }

    @Test
    fun routeEnds() {
        val ends = AutoClassify.ends("40.10000,-75.00000,200;40.00000,-75.00000,100")!!
        assertEquals(40.0 to -75.0, ends.first)
        assertEquals(40.1 to -75.0, ends.second)
        assertNull(AutoClassify.ends(""))
    }

    @Test
    fun daysRoundTrip() {
        assertEquals(work.days, WorkHours.parseDays(work.daysText()))
        assertEquals(emptySet<DayOfWeek>(), WorkHours.parseDays("9,x,"))
    }
}
