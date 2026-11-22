package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.DateRange
import org.openmiles.app.domain.PeriodSummary
import org.openmiles.app.domain.ReminderTime
import org.openmiles.app.domain.Summaries
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class NotificationSummaryTest {
    private val zone = ZoneId.of("America/New_York")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val mile = 1609.344
    private val day = LocalDate.of(2026, 11, 20)

    @Test
    fun weeklyAtAChosenDayAndHour() {
        // Sunday Nov 22, 2026, 8 AM -> Monday Nov 23 at 8 AM
        assertEquals(ms(2026, 11, 23, 8), ReminderTime.next(ms(2026, 11, 22, 8), zone, DayOfWeek.MONDAY, 8))
        // Monday at 8 AM exactly -> the next Monday
        assertEquals(ms(2026, 11, 30, 8), ReminderTime.next(ms(2026, 11, 23, 8), zone, DayOfWeek.MONDAY, 8))
        // Defaults stay Sunday 6 PM for people who never changed it
        assertEquals(ms(2026, 11, 22, 18), ReminderTime.next(ms(2026, 11, 22, 8), zone))
    }

    @Test
    fun weeklyKeepsTheHourAcrossDaylightSavingTime() {
        // Clocks go back Sunday Nov 1, 2026. The next Sunday 6 PM is still 6 PM local time.
        val at = ReminderTime.next(ms(2026, 10, 31, 12), zone)
        assertEquals(ms(2026, 11, 1, 18), at)
        assertEquals(18, java.time.Instant.ofEpochMilli(at).atZone(zone).hour)
    }

    @Test
    fun monthlyOnTheFirst() {
        assertEquals(ms(2026, 12, 1, 9), ReminderTime.nextMonthStart(ms(2026, 11, 22, 8), zone))
        // Before 9 AM on the 1st -> that same morning
        assertEquals(ms(2026, 12, 1, 9), ReminderTime.nextMonthStart(ms(2026, 12, 1, 8, 59), zone))
        // At or after 9 AM on the 1st -> next month
        assertEquals(ms(2027, 1, 1, 9), ReminderTime.nextMonthStart(ms(2026, 12, 1, 9), zone))
        // December rolls into January
        assertEquals(DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)),
            ReminderTime.previousMonth(ms(2027, 1, 1, 9), zone))
    }

    @Test
    fun boundsCoverTheWholeLastDay() {
        val (from, to) = ReminderTime.bounds(DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)), zone)
        assertEquals(ms(2026, 10, 1, 0), from)
        assertEquals(ms(2026, 11, 1, 0), to)
    }

    @Test
    fun labels() {
        assertEquals("12 AM", ReminderTime.hourLabel(0))
        assertEquals("8 AM", ReminderTime.hourLabel(8))
        assertEquals("12 PM", ReminderTime.hourLabel(12))
        assertEquals("6 PM", ReminderTime.hourLabel(18))
        assertEquals("Monday", ReminderTime.dayLabel(DayOfWeek.MONDAY))
    }

    @Test
    fun addsUpDrivesByKind() {
        val kinds = mapOf(1L to "BUSINESS", 2L to "PERSONAL", 3L to "MEDICAL")
        val s = Summaries.build(
            listOf(
                Triple(1L, 10 * mile, day),
                Triple(2L, 5 * mile, day),
                Triple(3L, 2 * mile, day),
                Triple(null, 3 * mile, day),
                // A category that was deleted counts as waiting, like on the Drives tab.
                Triple(99L, 1 * mile, day),
                // Bad numbers never poison the totals.
                Triple(1L, Double.NaN, day)
            ),
            kindOf = { id -> id?.let { kinds[it] } },
            rateOf = { kind, _ -> when (kind) { "BUSINESS" -> 0.70; "MEDICAL" -> 0.21; else -> 0.0 } }
        )
        assertEquals(6, s.drives)
        assertEquals(21.0, s.meters / mile, 1e-9)
        assertEquals(10.0, s.businessMeters / mile, 1e-9)
        assertEquals(7.42, s.value, 1e-9)
        assertEquals(2, s.unclassified)
    }

    @Test
    fun weeklyText() {
        val s = PeriodSummary(drives = 6, meters = 84.2 * mile, businessMeters = 52.0 * mile, value = 36.4, unclassified = 2)
        assertEquals(
            "Past 7 days: 6 drives, 84.2 mi. Business 52.0 mi. Worth \$36.40. 3 drives need a category.",
            Summaries.weeklyText(s, waitingTotal = 3)
        )
        // A personal-only week leaves out business and value.
        val personal = PeriodSummary(1, 4.0 * mile, 0.0, 0.0, 0)
        assertEquals("Past 7 days: 1 drive, 4.0 mi.", Summaries.weeklyText(personal, 0))
        // A quiet week with old drives still waiting falls back to the count.
        assertEquals("1 drive is waiting for a category.", Summaries.weeklyText(PeriodSummary(0, 0.0, 0.0, 0.0, 0), 1))
        // Nothing at all: no notification.
        assertNull(Summaries.weeklyText(PeriodSummary(0, 0.0, 0.0, 0.0, 0), 0))
    }

    @Test
    fun monthlyText() {
        val s = PeriodSummary(42, 610.3 * mile, 400.1 * mile, 280.07, 0)
        assertEquals(
            "October: 42 drives, 610.3 mi. Business 400.1 mi. Worth \$280.07. 1 drive needs a category.",
            Summaries.monthlyText("October", s, 1)
        )
        assertNull(Summaries.monthlyText("October", PeriodSummary(0, 0.0, 0.0, 0.0, 0), 5))
    }

    @Test
    fun autoClassifiedTextIsShortAndHasNoDashes() {
        val text = Summaries.autoClassifiedText(12.4 * mile, "Business")
        assertEquals("Drive saved, 12.4 mi. Marked Business by a rule. Tap to change it.", text)
        assertTrue(Summaries.autoClassifiedText(mile, "x".repeat(200)).length < 120)
    }
}
