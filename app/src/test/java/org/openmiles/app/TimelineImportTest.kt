package org.openmiles.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openmiles.app.data.TimeSpan
import org.openmiles.app.domain.TimelineImport
import java.io.StringReader
import java.time.ZoneId

/** Uses a small made-up file in the same shape as a real Android Timeline export. */
class TimelineImportTest {
    private val zone = ZoneId.of("America/New_York")

    private fun drive(start: String, end: String, meters: Double, type: String = "IN_PASSENGER_VEHICLE") = """
        {"startTime":"$start","endTime":"$end","startTimeTimezoneUtcOffsetMinutes":-240,
         "activity":{"start":{"latLng":"10.0000000°, -20.0000000°"},"end":{"latLng":"10.0500000°, -20.0500000°"},
         "distanceMeters":$meters,"probability":0.99,"topCandidate":{"type":"$type","probability":0.98},
         "parking":{"location":{"latLng":"10.05°, -20.05°"},"startTime":"$end"}}}
    """.trimIndent()

    private val sample = """
        {"semanticSegments":[
          {"startTime":"2025-12-31T20:00:00.000-05:00","endTime":"2026-01-01T08:00:00.000-05:00",
           "visit":{"hierarchyLevel":0,"topCandidate":{"placeId":"x"}}},
          ${drive("2025-12-31T09:00:00.000-05:00", "2025-12-31T09:20:00.000-05:00", 12000.5)},
          ${drive("2026-01-02T10:00:00.000-05:00", "2026-01-02T10:03:00.000-05:00", 300.0)},
          ${drive("2026-01-03T10:00:00.000-05:00", "2026-01-03T10:30:00.000-05:00", 25000.0)},
          ${drive("2026-01-04T10:00:00.000-05:00", "2026-01-04T10:30:00.000-05:00", 1500.0, "WALKING")},
          ${drive("2026-01-05T10:00:00.000-05:00", "2026-01-05T09:30:00.000-05:00", 5000.0)},
          ${drive("not a time", "2026-01-06T09:30:00.000-05:00", 5000.0)},
          ${drive("2026-01-07T10:00:00.000-05:00", "2026-01-07T10:30:00.000-05:00", -4.0)},
          {"startTime":"2026-01-03T10:00:00.000-05:00","endTime":"2026-01-03T10:30:00.000-05:00",
           "timelinePath":[{"point":"10.01°, -20.01°","time":"2026-01-03T10:05:00.000-05:00"}]}
        ],
        "rawSignals":[{"position":{"LatLng":"10.0°, -20.0°"}}],
        "userLocationProfile":{"frequentPlaces":[]}}
    """.trimIndent()

    @Test
    fun keepsOnlyValidCarDrives() {
        val parsed = TimelineImport.parse(StringReader(sample))
        assertEquals(3, parsed.drives.size)
        assertEquals(1, parsed.otherTrips) // the walk
        assertEquals(3, parsed.rejected) // end before start, bad time, negative distance
        val first = parsed.drives.first()
        assertEquals(12000.5, first.meters, 1e-9)
        assertEquals(10.0, first.startLat, 1e-9)
        assertEquals(-20.05, first.endLng, 1e-9)
        assertEquals(20 * 60_000L, first.endMs - first.startMs)
    }

    @Test
    fun planHandlesYearsShortDrivesAndOverlaps() {
        val parsed = TimelineImport.parse(StringReader(sample))
        val d = parsed.drives
        // An existing OpenMiles drive overlaps the January 3 drive.
        val existing = listOf(TimeSpan(d[2].startMs + 60_000, d[2].startMs + 120_000))
        val overlap = TimelineImport.overlaps(d, existing)
        assertArrayEquals(booleanArrayOf(false, false, true), overlap)

        val all = TimelineImport.plan(d, overlap, skipShort = true, years = setOf(2025, 2026), zone = zone)
        assertEquals(1, all.toAdd.size)
        assertEquals(1, all.skippedOverlap)
        assertEquals(1, all.skippedShort)

        val only2026 = TimelineImport.plan(d, overlap, skipShort = false, years = setOf(2026), zone = zone)
        assertEquals(1, only2026.skippedYear)
        assertEquals(1, only2026.toAdd.size) // the short one, since short drives are allowed now
    }

    @Test
    fun touchingDrivesAreNotOverlaps() {
        val parsed = TimelineImport.parse(StringReader(sample))
        val d = parsed.drives[2]
        val before = TimeSpan(d.startMs - 600_000, d.startMs) // ends exactly when this one starts
        assertArrayEquals(booleanArrayOf(false), TimelineImport.overlaps(listOf(d), listOf(before)))
    }

    @Test
    fun duplicatesInsideOneFileAreAddedOnce() {
        val twice = """{"semanticSegments":[
            ${drive("2026-02-01T10:00:00.000-05:00", "2026-02-01T10:30:00.000-05:00", 9000.0)},
            ${drive("2026-02-01T10:00:00.000-05:00", "2026-02-01T10:30:00.000-05:00", 9000.0)}]}"""
        val parsed = TimelineImport.parse(StringReader(twice))
        val plan = TimelineImport.plan(parsed.drives, BooleanArray(2), skipShort = true, years = setOf(2026), zone = zone)
        assertEquals(1, plan.toAdd.size)
    }

    @Test
    fun otherFilesAreRefusedClearly() {
        assertThrows(TimelineImport.NotATimelineFile::class.java) {
            TimelineImport.parse(StringReader("""[{"startTime":"2026-01-01"}]"""))
        }
        assertThrows(TimelineImport.NotATimelineFile::class.java) {
            TimelineImport.parse(StringReader("""{"timelineObjects":[]}"""))
        }
    }

    @Test
    fun latLngParsing() {
        assertEquals(40.1234567 to -75.7654321, TimelineImport.parseLatLng("40.1234567°, -75.7654321°"))
        assertNull(TimelineImport.parseLatLng("91.0°, 0.0°"))
        assertNull(TimelineImport.parseLatLng("abc"))
        assertNull(TimelineImport.parseLatLng(null))
    }
}
