package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Seed
import org.openmiles.app.data.TripSummary
import org.openmiles.app.domain.Calc
import org.openmiles.app.domain.Csv
import java.time.LocalDate
import java.time.ZoneId

class CalcTest {
    private val rates = Seed.defaultRates

    private fun millis(y: Int, m: Int, d: Int, hour: Int = 12): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault())
            .plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun trip(
        miles: Double,
        whenMs: Long,
        categoryId: Long? = null,
        notes: String = ""
    ) = TripSummary(
        id = 1,
        startTime = whenMs,
        endTime = whenMs + 600_000,
        distanceMeters = miles * Calc.METERS_PER_MILE,
        categoryId = categoryId,
        notes = notes
    )

    @Test
    fun businessRateChangesExactlyOnJuly1() {
        assertEquals(0.725, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2026, 6, 30), rates), 1e-9)
        assertEquals(0.76, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2026, 7, 1), rates), 1e-9)
        assertEquals(0.76, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2026, 12, 31), rates), 1e-9)
    }

    @Test
    fun ratesFor2025AreStillUsedForOldDrives() {
        assertEquals(0.70, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2025, 12, 31), rates), 1e-9)
        assertEquals(0.21, Calc.rateFor(Kind.MEDICAL, LocalDate.of(2025, 6, 1), rates), 1e-9)
    }

    @Test
    fun medicalAndCharityRates() {
        assertEquals(0.205, Calc.rateFor(Kind.MEDICAL, LocalDate.of(2026, 3, 1), rates), 1e-9)
        assertEquals(0.235, Calc.rateFor(Kind.MEDICAL, LocalDate.of(2026, 9, 1), rates), 1e-9)
        assertEquals(0.14, Calc.rateFor(Kind.CHARITY, LocalDate.of(2026, 9, 1), rates), 1e-9)
    }

    @Test
    fun personalIsAlwaysZeroAndMissingRatesAreZero() {
        assertEquals(0.0, Calc.rateFor(Kind.PERSONAL, LocalDate.of(2026, 9, 1), rates), 0.0)
        // Before any rate exists
        assertEquals(0.0, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2024, 1, 1), rates), 0.0)
    }

    @Test
    fun badDateInRateListIsIgnoredInsteadOfCrashing() {
        val broken = rates + Rate(kind = Kind.BUSINESS, effectiveFrom = "not-a-date", dollarsPerMile = 9.0)
        assertEquals(0.76, Calc.rateFor(Kind.BUSINESS, LocalDate.of(2026, 8, 1), broken), 1e-9)
    }

    @Test
    fun tenBusinessMilesInAugust2026() {
        val business = Category(id = 1, name = "Business", kind = Kind.BUSINESS, builtIn = true)
        val value = Calc.valueOf(trip(10.0, millis(2026, 8, 1)), business, rates)
        assertEquals(7.60, value, 1e-6)
    }

    @Test
    fun unclassifiedAndPersonalDrivesAreWorthNothing() {
        val personal = Category(id = 2, name = "Personal", kind = Kind.PERSONAL, builtIn = true)
        assertEquals(0.0, Calc.valueOf(trip(10.0, millis(2026, 8, 1)), null, rates), 0.0)
        assertEquals(0.0, Calc.valueOf(trip(10.0, millis(2026, 8, 1)), personal, rates), 0.0)
    }

    @Test
    fun customCategoryUsesTheRateOfItsKind() {
        val client = Category(id = 9, name = "Client visit", kind = Kind.BUSINESS)
        assertEquals(7.60, Calc.valueOf(trip(10.0, millis(2026, 8, 1)), client, rates), 1e-6)
    }

    @Test
    fun metersToMiles() {
        assertEquals(1.0, Calc.miles(1609.344), 1e-9)
    }

    @Test
    fun csvQuotesFieldsAndBlocksSpreadsheetFormulas() {
        val business = Category(id = 1, name = "Business", kind = Kind.BUSINESS, builtIn = true)
        val t = trip(
            miles = 5.0,
            whenMs = millis(2026, 8, 1),
            categoryId = 1,
            notes = "=HYPERLINK(\"http://bad\"), said \"hi\""
        )
        val csv = Csv.build(2026, listOf(t), listOf(business), rates)
        val lines = csv.trim().split("\n")
        assertEquals("Date,Start,End,Miles,Category,Type,Rate per mile,Value,Notes", lines[0])
        // Formula is neutralized with a leading apostrophe and quotes are doubled.
        assertTrue(csv, lines[1].contains("\"'=HYPERLINK(\"\"http://bad\"\"), said \"\"hi\"\"\""))
        assertTrue(lines[1].startsWith("2026-08-01,"))
        assertTrue(lines[1].contains(",5.00,\"Business\",\"Business\",0.760,3.80,"))
    }

    @Test
    fun csvOnlyIncludesTheRequestedYearAndLabelsUnclassified() {
        val t2025 = trip(1.0, millis(2025, 12, 31))
        val t2026 = trip(1.0, millis(2026, 1, 1))
        val csv = Csv.build(2026, listOf(t2025, t2026), emptyList(), rates)
        val lines = csv.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("\"Unclassified\""))
        assertFalse(csv.contains("2025-12-31"))
    }
}
