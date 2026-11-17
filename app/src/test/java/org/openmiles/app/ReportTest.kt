package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Seed
import org.openmiles.app.data.Source
import org.openmiles.app.data.TripSummary
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.DateRange
import org.openmiles.app.domain.Report
import org.openmiles.app.domain.Validation
import java.time.LocalDate
import java.time.ZoneId

class ReportTest {
    private val biz = Category(1, "Business", Kind.BUSINESS, true)
    private val per = Category(2, "Personal", Kind.PERSONAL, true)
    private fun ms(m: Int, d: Int) = LocalDate.of(2026, m, d).atStartOfDay(ZoneId.systemDefault()).plusHours(9).toInstant().toEpochMilli()
    private fun t(id: Long, m: Int, d: Int, miles: Double, cat: Long?, parking: Long = 0, tolls: Long = 0, edited: Boolean = false) =
        TripSummary(id, ms(m, d), ms(m, d) + 1_800_000, miles * Calc.METERS_PER_MILE, cat, "", Source.GPS, edited, parking, tolls)

    private val trips = listOf(
        t(1, 8, 3, 10.0, 1, parking = 1250, tolls = 300),
        t(2, 8, 4, 4.0, 2),
        t(3, 8, 5, 6.0, null, edited = true),
        t(4, 9, 1, 99.0, 1) // outside the range
    )

    @Test
    fun summaryAndRows() {
        val r = Report.build(
            DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), trips, listOf(biz, per), Seed.defaultRates,
            "Joshua", "2019 Car", null, null, "Nov 17, 2026"
        )
        assertEquals(3, r.rows.size)
        assertEquals("20.0 (3 drives)", r.summary.first { it.first == "Total miles" }.second)
        assertEquals("10.0, value \$7.60", r.summary.first { it.first == "Business miles" }.second)
        assertEquals("4.0", r.summary.first { it.first == "Personal miles" }.second)
        assertEquals("1 drives", r.summary.first { it.first == "Not classified" }.second)
        assertEquals("\$12.50 parking, \$3.00 tolls", r.summary.first { it.first == "Business parking and tolls" }.second)
        assertTrue(r.subtitle.contains("Driver: Joshua"))
        assertEquals("parking \$12.50; tolls \$3.00", r.rows[0][4])
        assertEquals("GPS, edited", r.rows[2][6])
        assertEquals("Not classified", r.rows[2][3])
        assertFalse(r.summary.any { it.first == "Odometer" })
    }

    @Test
    fun odometerAddsBusinessShare() {
        val r = Report.build(DateRange.year(2026), trips, listOf(biz, per), Seed.defaultRates, "", "", 10_000.0, 10_218.0, "x")
        assertEquals("10,000.0 to 10,218.0 = 218.0 miles driven", r.summary.first { it.first == "Odometer" }.second)
        assertEquals("50.0% of miles driven", r.summary.first { it.first == "Business use" }.second) // 109 of 218
        assertNull(Report.odometerTotal(500.0, 400.0))
        assertNull(Report.odometerTotal(null, 400.0))
    }

    @Test
    fun moneyText() {
        assertEquals("\$0.05", Report.money(5))
        assertEquals("\$1,234.50", Report.money(123_450))
    }

    @Test
    fun parseMoney() {
        assertEquals(0L, Validation.parseCents(""))
        assertEquals(1250L, Validation.parseCents("12.50"))
        assertEquals(1250L, Validation.parseCents("$12.5"))
        assertEquals(1200L, Validation.parseCents("12."))
        assertEquals(100_000L, Validation.parseCents("1000"))
        assertNull(Validation.parseCents("1000.01"))
        assertNull(Validation.parseCents("12.345"))
        assertNull(Validation.parseCents("-3"))
        assertNull(Validation.parseCents("abc"))
        assertEquals("12.50", Validation.centsText(1250))
        assertEquals("", Validation.centsText(0))
    }
}
