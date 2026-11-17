package org.openmiles.app.domain

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.openmiles.app.data.TimeSpan
import java.io.Reader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** One drive found in a Google Maps Timeline export. Only the ends of the drive are kept. */
data class ImportedDrive(
    val startMs: Long,
    val endMs: Long,
    val meters: Double,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double
) {
    fun year(zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochMilli(startMs).atZone(zone).year
}

/**
 * Reads the Timeline file that Android exports from Settings, Location, Location services,
 * Timeline, Export Timeline data. The file can be 50 MB or more and holds all location history,
 * so it is read piece by piece and only car drives ("IN_PASSENGER_VEHICLE") are kept.
 * Nothing is sent anywhere.
 */
object TimelineImport {
    class NotATimelineFile(message: String) : Exception(message)

    data class Parsed(
        val drives: List<ImportedDrive>,
        /** Walks, bike rides and other trips that are not drives. */
        val otherTrips: Int,
        /** Drives with missing or impossible values, left out. */
        val rejected: Int
    )

    data class Plan(
        val toAdd: List<ImportedDrive>,
        val skippedOverlap: Int,
        val skippedShort: Int,
        val skippedYear: Int
    )

    const val DRIVE_TYPE = "IN_PASSENGER_VEHICLE"
    const val SHORT_DRIVE_M = 402.336 // a quarter mile
    private const val MAX_DRIVE_M = 2_000_000.0
    private const val MAX_DRIVE_MS = 24 * 3_600_000L
    private const val MAX_DRIVES = 200_000

    fun parse(input: Reader): Parsed {
        val reader = JsonReader(input)
        val drives = mutableListOf<ImportedDrive>()
        var other = 0
        var rejected = 0
        var sawSegments = false

        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw notTimeline()
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "semanticSegments" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                sawSegments = true
                reader.beginArray()
                while (reader.hasNext()) {
                    when (val r = readSegment(reader)) {
                        is SegmentResult.Drive -> if (drives.size < MAX_DRIVES) drives += r.drive
                        SegmentResult.OtherTrip -> other++
                        SegmentResult.Bad -> rejected++
                        SegmentResult.NotATrip -> Unit
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        if (!sawSegments) throw notTimeline()
        return Parsed(drives, other, rejected)
    }

    private fun notTimeline() = NotATimelineFile(
        "This doesn't look like a Timeline file from an Android phone. On your phone, open Settings, " +
            "Location, Location services, Timeline, and tap Export Timeline data."
    )

    private sealed class SegmentResult {
        data class Drive(val drive: ImportedDrive) : SegmentResult()
        object OtherTrip : SegmentResult()
        object Bad : SegmentResult()
        object NotATrip : SegmentResult()
    }

    private class Activity {
        var start: String? = null
        var end: String? = null
        var meters: Double? = null
        var type: String? = null
    }

    private fun readSegment(reader: JsonReader): SegmentResult {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return SegmentResult.NotATrip
        }
        var startTime: String? = null
        var endTime: String? = null
        var activity: Activity? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "startTime" -> startTime = readString(reader)
                "endTime" -> endTime = readString(reader)
                "activity" -> activity = readActivity(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val a = activity ?: return SegmentResult.NotATrip
        if (a.type != DRIVE_TYPE) return SegmentResult.OtherTrip
        val startMs = parseTime(startTime) ?: return SegmentResult.Bad
        val endMs = parseTime(endTime) ?: return SegmentResult.Bad
        val meters = a.meters ?: return SegmentResult.Bad
        val from = parseLatLng(a.start) ?: return SegmentResult.Bad
        val to = parseLatLng(a.end) ?: return SegmentResult.Bad
        if (endMs <= startMs || endMs - startMs > MAX_DRIVE_MS) return SegmentResult.Bad
        if (!meters.isFinite() || meters <= 0.0 || meters > MAX_DRIVE_M) return SegmentResult.Bad
        return SegmentResult.Drive(ImportedDrive(startMs, endMs, meters, from.first, from.second, to.first, to.second))
    }

    private fun readActivity(reader: JsonReader): Activity? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        val a = Activity()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "start" -> a.start = readLatLngHolder(reader)
                "end" -> a.end = readLatLngHolder(reader)
                "distanceMeters" -> a.meters = readDouble(reader)
                "topCandidate" -> a.type = readType(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return a
    }

    private fun readLatLngHolder(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var value: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "latLng") value = readString(reader) else reader.skipValue()
        }
        reader.endObject()
        return value
    }

    private fun readType(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var type: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "type") type = readString(reader) else reader.skipValue()
        }
        reader.endObject()
        return type
    }

    private fun readString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString().take(100) else {
            reader.skipValue()
            null
        }

    private fun readDouble(reader: JsonReader): Double? =
        if (reader.peek() == JsonToken.NUMBER || reader.peek() == JsonToken.STRING) {
            runCatching { reader.nextString().toDouble() }.getOrNull()
        } else {
            reader.skipValue()
            null
        }

    fun parseTime(text: String?): Long? =
        text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** Reads "40.1234567°, -75.1234567°". Returns null for anything else or impossible values. */
    fun parseLatLng(text: String?): Pair<Double, Double>? {
        if (text == null) return null
        val parts = text.replace("°", "").split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    /** Marks each found drive that overlaps a drive already in OpenMiles. */
    fun overlaps(drives: List<ImportedDrive>, existing: List<TimeSpan>): BooleanArray {
        val sorted = existing.sortedBy { it.startTime }
        val starts = LongArray(sorted.size) { sorted[it].startTime }
        // The longest existing drive bounds how far back an overlapping drive can start.
        val longest = sorted.maxOfOrNull { it.endTime - it.startTime } ?: 0L
        return BooleanArray(drives.size) { i ->
            val d = drives[i]
            var j = lowerBound(starts, d.startMs - longest)
            var hit = false
            while (j < sorted.size && sorted[j].startTime < d.endMs) {
                if (sorted[j].endTime > d.startMs) {
                    hit = true
                    break
                }
                j++
            }
            hit
        }
    }

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Decides which drives to add, given the person's choices on the preview screen. */
    fun plan(
        drives: List<ImportedDrive>,
        overlapping: BooleanArray,
        skipShort: Boolean,
        years: Set<Int>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Plan {
        val toAdd = mutableListOf<ImportedDrive>()
        var overlap = 0
        var short = 0
        var year = 0
        drives.forEachIndexed { i, d ->
            when {
                d.year(zone) !in years -> year++
                overlapping[i] -> overlap++
                skipShort && d.meters < SHORT_DRIVE_M -> short++
                else -> toAdd += d
            }
        }
        // Drives can repeat inside one file (for example, two exports joined). Keep one of each.
        val unique = toAdd.distinctBy { it.startMs to it.endMs }
        return Plan(unique, overlap + (toAdd.size - unique.size), short, year)
    }
}
