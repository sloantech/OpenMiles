package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.data.Kind
import org.openmiles.app.data.TimeSpan
import org.openmiles.app.domain.MileIqImport
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneId

class MileIqImportTest {
    private val zone = ZoneId.of("America/New_York")

    /** Same layout as a real MileIQ monthly CSV report, with made-up places. */
    private val sample = """
        rates >>>,business $,0.76,medical $,0.235,charity $,0.14,moving $,0.235

        SUMMARY

        VEHICLE,ODOMETER (START OF YEAR),BUSINESS,COMMUTE,PERSONAL (OTHER),TOTAL DISTANCE
        Test Truck 2011,0,"=SUMIFS(G12:G21, L12:L21, A6, C12:C21, ""Business"")","=SUM(C6, D6)"
        ,TOTALS,=SUM(C6:C6),=SUM(D6:D6)

        DETAILED LOG

        START_DATE*,END_DATE*,CATEGORY*,START*,STOP*,RATE,MILES*,MILES_VALUE,PARKING,TOLLS,TOTAL,VEHICLE,PURPOSE,NOTES
        11/03/2026 17:29,11/03/2026 17:31,Business,"1 Main St, Testville, PA, 19000, United States",Corner Store,0.76,1.3,"=ROUND(PRODUCT(F12, G12), 2)",0,0,"=SUM(H12, I12, J12)",Test Truck 2011,Fuel,
        11/08/2026 07:05,11/08/2026 07:07,Business,Home,"Flea Market, ""North"" lot",0.76,2.1,"=ROUND(PRODUCT(F16, G16), 2)",2.50,1.25,"=SUM(H16, I16, J16)",Test Truck 2011,Customer Visit,"Met Bob,
        second line"
        11/09/2026 09:00,11/09/2026 09:30,Personal,Home,Clinic,0.235,8.0,,0,0,,Test Truck 2011,Medical,
        11/10/2026 10:00,11/10/2026 10:20,Personal,Home,Food bank,0.14,5.5,,0,0,,Test Truck 2011,Charity,
        11/11/2026 11:00,11/11/2026 11:10,Personal,Home,Gym,0,3.0,,0,0,,Test Truck 2011,Commute,
        11/12/2026 12:00,11/12/2026 12:00,Unclassified,Home,Store,0,0.4,,0,0,,Test Truck 2011,,
        11/13/2026 12:00,11/13/2026 11:00,Business,Home,Store,0.76,1.0,,0,0,,Test Truck 2011,,
        11/14/2026 12:00,11/14/2026 12:30,Business,Home,Store,0.76,=G1,,0,0,,Test Truck 2011,,
        Totals,,,,,,=SUM(G11:G21),=SUM(H11:H21),=SUM(I11:I21),=SUM(J11:J21),=SUM(K11:K21)

        Report created at MileIQ.com


        "* For an IRS compliant log, these are required for every drive."
    """.trimIndent()

    private fun parse(text: String = sample) = MileIqImport.parse(StringReader(text), zone)

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun readsDrivesAndSkipsSummaryFormulasAndTotals() {
        val p = parse()
        assertEquals(6, p.drives.size)
        // End before start, and a formula instead of miles.
        assertEquals(2, p.rejected)
        assertFalse(p.usedKilometers)
    }

    @Test fun readsTimesMilesAndPlaces() {
        val d = parse().drives[0]
        assertEquals(ms(2026, 11, 3, 17, 29), d.startMs)
        assertEquals(ms(2026, 11, 3, 17, 31), d.endMs)
        assertEquals(1.3 * MileIqImport.METERS_PER_MILE, d.meters, 0.001)
        assertEquals(Kind.BUSINESS, d.kind)
        assertEquals("1 Main St, Testville, PA, 19000, United States", d.from)
        assertEquals("Corner Store", d.to)
        assertEquals("Fuel", d.purpose)
        assertEquals("Test Truck 2011", d.vehicle)
    }

    @Test fun keepsQuotesLineBreaksParkingAndTolls() {
        val d = parse().drives[1]
        assertEquals("Flea Market, \"North\" lot", d.to)
        assertEquals("Met Bob, second line", d.notes)
        assertEquals(250L, d.parkingCents)
        assertEquals(125L, d.tollsCents)
        assertEquals(
            "Home to Flea Market, \"North\" lot. Customer Visit. Met Bob, second line. Test Truck 2011",
            d.noteText(200)
        )
        assertEquals(20, d.noteText(20).length)
    }

    @Test fun mapsMileIqCategoriesAndPurposes() {
        val kinds = parse().drives.map { it.kind }
        assertEquals(listOf(Kind.BUSINESS, Kind.BUSINESS, Kind.MEDICAL, Kind.CHARITY, Kind.PERSONAL, null), kinds)
        assertNull(MileIqImport.kindOf("", ""))
        assertEquals(Kind.PERSONAL, MileIqImport.kindOf("Personal", "Moving"))
    }

    @Test fun sameMinuteDriveGetsOneMinute() {
        val d = parse().drives[5]
        assertEquals(60_000L, d.endMs - d.startMs)
    }

    @Test fun rejectsFilesThatAreNotMileIq() {
        val bad = listOf("", "hello", "Date,Miles\n1/1/2026,3", "{\"semanticSegments\": []}")
        bad.forEach {
            try {
                parse(it)
                throw AssertionError("should fail: $it")
            } catch (e: MileIqImport.NotAMileIqFile) {
                assertTrue(e.message!!.contains("MileIQ"))
            }
        }
    }

    @Test fun kilometerReportsAreConverted() {
        val km = "START_DATE,END_DATE,CATEGORY,KM\n2026-11-01 08:00,2026-11-01 08:30,Business,10\n"
        val p = parse(km)
        assertTrue(p.usedKilometers)
        assertEquals(10_000.0, p.drives.single().meters, 0.001)
    }

    @Test fun acceptsAmPmAndSecondsAndByteOrderMark() {
        val text = "﻿START_DATE*,END_DATE*,CATEGORY*,MILES*\r\n11/3/2026 5:29 PM,11/3/2026 5:31:30 PM,Business,1.0\r\n"
        val d = parse(text).drives.single()
        assertEquals(ms(2026, 11, 3, 17, 29), d.startMs)
    }

    @Test fun refusesImpossibleValues() {
        assertNull(MileIqImport.parseTime("13/45/2026 10:00", zone))
        assertNull(MileIqImport.parseTime("02/30/2026 10:00", zone))
        assertNull(MileIqImport.parseTime("01/01/1800 10:00", zone))
        assertNull(MileIqImport.parseNumber("-3"))
        assertNull(MileIqImport.parseNumber("1e9"))
        assertNull(MileIqImport.parseNumber("=G1"))
        assertEquals(1234.5, MileIqImport.parseNumber("1,234.5")!!, 0.0)
        assertEquals(0L, MileIqImport.money("=SUM(A1)"))
        assertEquals(0L, MileIqImport.money("-5"))
        assertEquals(1050L, MileIqImport.money("$10.50"))
        val huge = "START_DATE,END_DATE,MILES\n1/1/2026 1:00,1/1/2026 2:00,5000\n"
        assertEquals(1, parse(huge).rejected)
    }

    @Test fun planSkipsOverlapsDuplicatesAndYears() {
        val drives = parse().drives
        val existing = listOf(TimeSpan(drives[0].startMs + 30_000, drives[0].endMs + 60_000))
        val overlap = MileIqImport.overlaps(drives + drives[2], existing)
        assertTrue(overlap[0])
        assertFalse(overlap[1])
        val plan = MileIqImport.plan(drives + drives[2], overlap, setOf(2026), zone)
        assertEquals(5, plan.toAdd.size)
        assertEquals(2, plan.skippedOverlap) // one overlap, one repeat
        val none = MileIqImport.plan(drives, BooleanArray(drives.size), setOf(2025), zone)
        assertEquals(0, none.toAdd.size)
        assertEquals(drives.size, none.skippedYear)
    }
}
