package org.openmiles.app.domain

import org.openmiles.app.data.Kind
import org.openmiles.app.data.TimeSpan
import java.io.Reader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** One drive read from a MileIQ report. MileIQ reports have no map points, only place names. */
data class MileIqDrive(
    val startMs: Long,
    val endMs: Long,
    val meters: Double,
    /** One of the [Kind] values, or null when MileIQ had it unclassified. */
    val kind: String?,
    /** MileIQ's purpose, such as "Customer Visit" or "Commute". May be blank. */
    val purpose: String,
    val from: String,
    val to: String,
    val vehicle: String,
    val notes: String,
    val parkingCents: Long,
    val tollsCents: Long
) {
    fun year(zone: ZoneId = ZoneId.systemDefault()): Int =
        java.time.Instant.ofEpochMilli(startMs).atZone(zone).year

    /** The note saved with the drive in OpenMiles, so nothing from MileIQ is lost. */
    fun noteText(max: Int): String {
        val parts = mutableListOf<String>()
        if (from.isNotBlank() || to.isNotBlank()) parts += "${from.ifBlank { "?" }} to ${to.ifBlank { "?" }}"
        if (purpose.isNotBlank()) parts += purpose
        if (notes.isNotBlank()) parts += notes
        if (vehicle.isNotBlank()) parts += vehicle
        return parts.joinToString(". ").take(max)
    }
}

/**
 * Reads the CSV report that MileIQ emails or lets you download ("Download as CSV").
 * The file has a summary block on top and a "DETAILED LOG" table below it. The table is found by
 * its column names, so the summary, formulas and totals rows are ignored. Nothing is sent anywhere.
 */
object MileIqImport {
    class NotAMileIqFile(message: String) : Exception(message)

    data class Parsed(val drives: List<MileIqDrive>, val rejected: Int, val usedKilometers: Boolean)

    data class Plan(val toAdd: List<MileIqDrive>, val skippedOverlap: Int, val skippedYear: Int)

    private const val MAX_CHARS = 20_000_000
    private const val MAX_DRIVES = 200_000
    private const val MAX_DRIVE_MS = 24 * 3_600_000L
    private const val MAX_DRIVE_M = 2_000_000.0
    private const val MAX_TEXT = 120
    const val METERS_PER_MILE = 1609.344

    private val dateFormats = listOf(
        "M/d/uuuu H:mm", "M/d/uuuu H:mm:ss", "M/d/uuuu h:mm a", "M/d/uuuu h:mm:ss a",
        "uuuu-M-d H:mm", "uuuu-M-d H:mm:ss", "uuuu-M-d'T'H:mm", "uuuu-M-d'T'H:mm:ss"
    ).map { DateTimeFormatter.ofPattern(it, Locale.US).withResolverStyle(ResolverStyle.STRICT) }

    fun parse(input: Reader, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val text = readCapped(input)
        val rows = CsvReader.parseRows(text)

        val headerIndex = rows.indexOfFirst { row ->
            val names = row.map(::norm)
            "STARTDATE" in names && ("MILES" in names || "KM" in names || "KILOMETERS" in names)
        }
        if (headerIndex < 0) throw notMileIq()
        val header = rows[headerIndex].map(::norm)
        fun col(vararg names: String): Int = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1

        val cStart = col("STARTDATE")
        val cEnd = col("ENDDATE")
        val cMiles = col("MILES")
        val cKm = if (cMiles < 0) col("KM", "KILOMETERS") else -1
        val cCategory = col("CATEGORY")
        val cFrom = col("START", "FROM")
        val cTo = col("STOP", "END", "TO")
        val cParking = col("PARKING")
        val cTolls = col("TOLLS")
        val cVehicle = col("VEHICLE")
        val cPurpose = col("PURPOSE")
        val cNotes = col("NOTES")
        if (cStart < 0 || cEnd < 0 || (cMiles < 0 && cKm < 0)) throw notMileIq()

        val drives = mutableListOf<MileIqDrive>()
        var rejected = 0
        for (row in rows.drop(headerIndex + 1)) {
            if (row.all { it.isBlank() }) continue
            val first = row.firstOrNull()?.trim().orEmpty()
            if (first.equals("Totals", ignoreCase = true) || first.startsWith("Report created", ignoreCase = true) ||
                first.startsWith("*") || first.startsWith("\"*")
            ) continue
            val d = readDrive(row, zone, cStart, cEnd, cMiles, cKm, cCategory, cFrom, cTo, cParking, cTolls, cVehicle, cPurpose, cNotes)
            if (d == null) rejected++ else if (drives.size < MAX_DRIVES) drives += d
        }
        return Parsed(drives, rejected, cKm >= 0)
    }

    private fun readDrive(
        row: List<String>, zone: ZoneId,
        cStart: Int, cEnd: Int, cMiles: Int, cKm: Int, cCategory: Int, cFrom: Int, cTo: Int,
        cParking: Int, cTolls: Int, cVehicle: Int, cPurpose: Int, cNotes: Int
    ): MileIqDrive? {
        fun cell(i: Int) = if (i in row.indices) row[i].trim() else ""
        val start = parseTime(cell(cStart), zone) ?: return null
        val end = parseTime(cell(cEnd), zone) ?: return null
        if (end < start || end - start > MAX_DRIVE_MS) return null
        val distance = parseNumber(cell(if (cMiles >= 0) cMiles else cKm)) ?: return null
        val meters = if (cMiles >= 0) distance * METERS_PER_MILE else distance * 1000.0
        if (!meters.isFinite() || meters <= 0.0 || meters > MAX_DRIVE_M) return null
        val purpose = clean(cell(cPurpose))
        return MileIqDrive(
            startMs = start,
            // MileIQ shows minutes only, so a short drive can start and end in the same minute.
            endMs = if (end == start) start + 60_000L else end,
            meters = meters,
            kind = kindOf(cell(cCategory), purpose),
            purpose = purpose,
            from = clean(cell(cFrom)),
            to = clean(cell(cTo)),
            vehicle = clean(cell(cVehicle)),
            notes = clean(cell(cNotes)),
            parkingCents = money(cell(cParking)),
            tollsCents = money(cell(cTolls))
        )
    }

    /** Matches MileIQ's own rules: Business is business; Medical and Charity are purposes of Personal. */
    fun kindOf(category: String, purpose: String): String? {
        val c = category.trim().lowercase(Locale.US)
        val p = purpose.trim().lowercase(Locale.US)
        return when {
            c.startsWith("business") -> Kind.BUSINESS
            c.startsWith("medical") || (c.startsWith("personal") && "medical" in p) -> Kind.MEDICAL
            c.startsWith("charit") || (c.startsWith("personal") && "charit" in p) -> Kind.CHARITY
            c.startsWith("personal") -> Kind.PERSONAL
            else -> null
        }
    }

    fun parseTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        for (f in dateFormats) {
            val local = runCatching { LocalDateTime.parse(t.uppercase(Locale.US), f) }.getOrNull() ?: continue
            if (local.year !in 1990..2100) return null
            return local.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /** Plain numbers only. Formula cells such as "=ROUND(...)" are not numbers and return null. */
    fun parseNumber(text: String): Double? {
        val t = text.trim().removePrefix("$").replace(",", "")
        if (!Regex("""^\d{1,7}(\.\d{1,4})?$""").matches(t)) return null
        return t.toDoubleOrNull()
    }

    /** Dollars to cents. Blank, formulas or anything odd count as zero rather than failing the drive. */
    fun money(text: String): Long {
        val t = text.trim().replace(",", "")
        if (t.startsWith("=")) return 0L
        return Validation.parseCents(t) ?: 0L
    }

    private fun clean(text: String): String =
        text.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

    private fun norm(header: String): String = header.uppercase(Locale.US).replace(Regex("[^A-Z]"), "")

    private fun readCapped(input: Reader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            if (sb.length > MAX_CHARS) throw NotAMileIqFile("That file is too large to be a MileIQ report.")
        }
        return sb.toString().removePrefix("﻿")
    }

    private fun notMileIq() = NotAMileIqFile(
        "This doesn't look like a MileIQ report. In MileIQ, open a monthly report email or the online " +
            "dashboard and choose Download as CSV. Then pick that file here."
    )

    fun overlaps(drives: List<MileIqDrive>, existing: List<TimeSpan>): BooleanArray =
        Overlap.find(drives.map { TimeSpan(it.startMs, it.endMs) }, existing)

    fun plan(
        drives: List<MileIqDrive>,
        overlapping: BooleanArray,
        years: Set<Int>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Plan {
        val toAdd = mutableListOf<MileIqDrive>()
        var overlap = 0
        var year = 0
        drives.forEachIndexed { i, d ->
            when {
                d.year(zone) !in years -> year++
                overlapping[i] -> overlap++
                else -> toAdd += d
            }
        }
        // The same drive can be in two reports (a monthly and a yearly one). Keep one of each.
        val unique = toAdd.distinctBy { it.startMs to it.endMs }
        return Plan(unique, overlap + (toAdd.size - unique.size), year)
    }

    /** A small CSV reader: quoted fields, doubled quotes, commas and line breaks inside quotes. */
    object CsvReader {
        fun parseRows(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            quoted = false
                        }
                    } else {
                        field.append(c)
                    }
                } else {
                    when (c) {
                        '"' -> quoted = true
                        ',' -> {
                            row += field.toString()
                            field.setLength(0)
                        }
                        '\r' -> Unit
                        '\n' -> {
                            row += field.toString()
                            field.setLength(0)
                            rows += row
                            row = mutableListOf()
                        }
                        else -> field.append(c)
                    }
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row += field.toString()
                rows += row
            }
            return rows
        }
    }
}

/** Finds which new drives overlap a drive already saved. Shared by the import screens. */
object Overlap {
    fun find(drives: List<TimeSpan>, existing: List<TimeSpan>): BooleanArray {
        val sorted = existing.sortedBy { it.startTime }
        val starts = LongArray(sorted.size) { sorted[it].startTime }
        val longest = sorted.maxOfOrNull { it.endTime - it.startTime } ?: 0L
        return BooleanArray(drives.size) { i ->
            val d = drives[i]
            var lo = 0
            var hi = starts.size
            val key = d.startTime - longest
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < key) lo = mid + 1 else hi = mid
            }
            var j = lo
            var hit = false
            while (j < sorted.size && sorted[j].startTime < d.endTime) {
                if (sorted[j].endTime > d.startTime) {
                    hit = true
                    break
                }
                j++
            }
            hit
        }
    }
}
