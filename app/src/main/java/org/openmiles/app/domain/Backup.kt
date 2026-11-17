package org.openmiles.app.domain

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer

/*
 * Backup files. One JSON file holds everything needed to rebuild the log on a new phone:
 * categories, rates and drives. It is written and read piece by piece so a large log never
 * runs the phone out of memory. Restoring only adds what is missing; it never deletes.
 */

data class BackupCategory(val id: Long, val name: String, val kind: String, val builtIn: Boolean)
data class BackupRate(val kind: String, val effectiveFrom: String, val dollarsPerMile: Double)
data class BackupTrip(
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Double,
    val categoryId: Long?,
    val notes: String,
    val route: String,
    val source: String,
    val edited: Boolean = false,
    val parkingCents: Long = 0,
    val tollsCents: Long = 0
)

data class BackupOdometer(val year: Int, val startMiles: Double?, val endMiles: Double?)

data class BackupPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val categoryId: Long?,
    val ignoreDrives: Boolean
)

/** Everything in a backup except the drives, which are handed over one at a time. */
data class BackupInfo(
    val formatVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val categories: List<BackupCategory>,
    val rates: List<BackupRate>,
    val trips: Int,
    val rejected: Int,
    val places: List<BackupPlace> = emptyList(),
    /** Null when the backup has no work-hours rule (for example, from an older version). */
    val workHours: WorkHours? = null,
    val odometer: List<BackupOdometer> = emptyList()
)

object BackupCodec {
    const val FORMAT = "openmiles-backup"
    const val VERSION = 1

    private val KINDS = setOf("BUSINESS", "PERSONAL", "MEDICAL", "CHARITY")
    private val SOURCES = setOf("gps", "manual", "timeline")
    private const val MAX_NAME = 40
    private const val MAX_NOTES = 200
    private const val MAX_ROUTE = 2_000_000
    private const val MAX_METERS = 2_000_000.0
    private const val MAX_CATEGORIES = 1_000
    private const val MAX_RATES = 1_000
    private const val MAX_PLACES = 1_000

    class NotABackup(message: String) : Exception(message)

    /** Writes a backup. [eachTrip] is called once and hands every drive to [emit]. */
    fun write(
        out: Writer,
        appVersion: String,
        createdAt: Long,
        categories: List<BackupCategory>,
        rates: List<BackupRate>,
        places: List<BackupPlace> = emptyList(),
        workHours: WorkHours? = null,
        odometer: List<BackupOdometer> = emptyList(),
        eachTrip: (emit: (BackupTrip) -> Unit) -> Unit
    ): Int {
        var count = 0
        val w = JsonWriter(out)
        w.beginObject()
        w.name("format").value(FORMAT)
        w.name("version").value(VERSION.toLong())
        w.name("appVersion").value(appVersion)
        w.name("createdAt").value(createdAt)
        w.name("categories").beginArray()
        categories.forEach {
            w.beginObject()
            w.name("id").value(it.id)
            w.name("name").value(it.name)
            w.name("kind").value(it.kind)
            w.name("builtIn").value(it.builtIn)
            w.endObject()
        }
        w.endArray()
        w.name("rates").beginArray()
        rates.forEach {
            w.beginObject()
            w.name("kind").value(it.kind)
            w.name("effectiveFrom").value(it.effectiveFrom)
            w.name("dollarsPerMile").value(it.dollarsPerMile)
            w.endObject()
        }
        w.endArray()
        w.name("places").beginArray()
        places.forEach {
            w.beginObject()
            w.name("name").value(it.name)
            w.name("lat").value(it.lat)
            w.name("lng").value(it.lng)
            w.name("radiusMeters").value(it.radiusMeters)
            if (it.categoryId != null) w.name("categoryId").value(it.categoryId)
            w.name("ignoreDrives").value(it.ignoreDrives)
            w.endObject()
        }
        w.endArray()
        if (workHours != null) {
            w.name("workHours").beginObject()
            w.name("enabled").value(workHours.enabled)
            w.name("days").value(workHours.daysText())
            w.name("startMin").value(workHours.startMin.toLong())
            w.name("endMin").value(workHours.endMin.toLong())
            if (workHours.categoryId != null) w.name("categoryId").value(workHours.categoryId)
            w.endObject()
        }
        w.name("odometer").beginArray()
        odometer.forEach {
            w.beginObject()
            w.name("year").value(it.year.toLong())
            if (it.startMiles != null) w.name("startMiles").value(it.startMiles)
            if (it.endMiles != null) w.name("endMiles").value(it.endMiles)
            w.endObject()
        }
        w.endArray()
        w.name("trips").beginArray()
        eachTrip { t ->
            w.beginObject()
            w.name("startTime").value(t.startTime)
            w.name("endTime").value(t.endTime)
            w.name("distanceMeters").value(t.distanceMeters)
            if (t.categoryId != null) w.name("categoryId").value(t.categoryId)
            w.name("notes").value(t.notes)
            w.name("route").value(t.route)
            w.name("source").value(t.source)
            if (t.edited) w.name("edited").value(true)
            if (t.parkingCents > 0) w.name("parkingCents").value(t.parkingCents)
            if (t.tollsCents > 0) w.name("tollsCents").value(t.tollsCents)
            w.endObject()
            count++
        }
        w.endArray()
        w.endObject()
        w.flush()
        return count
    }

    /**
     * Reads a backup. Valid drives go to [onTrip] one at a time; anything damaged or odd is
     * counted as rejected and left out. A file that is not an OpenMiles backup is refused.
     */
    fun read(input: Reader, onTrip: (BackupTrip) -> Unit): BackupInfo {
        val r = JsonReader(input)
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw notBackup()
        var format: String? = null
        var version = -1
        var appVersion = ""
        var createdAt = 0L
        val categories = mutableListOf<BackupCategory>()
        val rates = mutableListOf<BackupRate>()
        val places = mutableListOf<BackupPlace>()
        var work: WorkHours? = null
        val odometer = mutableListOf<BackupOdometer>()
        var trips = 0
        var rejected = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = str(r)
                "version" -> version = num(r)?.toInt() ?: -1
                "appVersion" -> appVersion = str(r)?.take(20) ?: ""
                "createdAt" -> createdAt = num(r)?.toLong() ?: 0L
                "categories" -> array(r) {
                    val c = readCategory(r)
                    if (c != null && categories.size < MAX_CATEGORIES) categories += c else rejected++
                }
                "rates" -> array(r) {
                    val x = readRate(r)
                    if (x != null && rates.size < MAX_RATES) rates += x else rejected++
                }
                "places" -> array(r) {
                    val p = readPlace(r)
                    if (p != null && places.size < MAX_PLACES) places += p else rejected++
                }
                "workHours" -> work = readWorkHours(r)
                "odometer" -> array(r) {
                    val o = readOdometer(r)
                    if (o != null && odometer.size < 200 && odometer.none { it.year == o.year }) odometer += o else rejected++
                }
                "trips" -> {
                    // The header must come first, so a wrong file is refused before any drive is used.
                    if (format != FORMAT) throw notBackup()
                    if (version > VERSION) throw NotABackup(
                        "This backup was made by a newer version of OpenMiles. Update the app, then restore."
                    )
                    array(r) {
                        val t = readTrip(r)
                        if (t != null) {
                            trips++
                            onTrip(t)
                        } else {
                            rejected++
                        }
                    }
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (format != FORMAT || version < 1) throw notBackup()
        return BackupInfo(version, appVersion, createdAt, categories, rates, trips, rejected, places, work, odometer)
    }

    private fun notBackup() = NotABackup("That file is not an OpenMiles backup.")

    private inline fun array(r: JsonReader, each: () -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) each()
        r.endArray()
    }

    private fun str(r: JsonReader): String? =
        if (r.peek() == JsonToken.STRING) r.nextString() else {
            r.skipValue()
            null
        }

    private fun num(r: JsonReader): Double? =
        if (r.peek() == JsonToken.NUMBER) runCatching { r.nextString().toDouble() }.getOrNull() else {
            r.skipValue()
            null
        }

    private fun bool(r: JsonReader): Boolean? =
        if (r.peek() == JsonToken.BOOLEAN) r.nextBoolean() else {
            r.skipValue()
            null
        }

    private inline fun obj(r: JsonReader, field: (String) -> Unit): Boolean {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return false
        }
        r.beginObject()
        while (r.hasNext()) field(r.nextName())
        r.endObject()
        return true
    }

    private fun readCategory(r: JsonReader): BackupCategory? {
        var id: Double? = null
        var name: String? = null
        var kind: String? = null
        var builtIn: Boolean? = null
        if (!obj(r) {
                when (it) {
                    "id" -> id = num(r)
                    "name" -> name = str(r)
                    "kind" -> kind = str(r)
                    "builtIn" -> builtIn = bool(r)
                    else -> r.skipValue()
                }
            }
        ) return null
        val n = name?.trim()?.take(MAX_NAME)
        if (id == null || n.isNullOrEmpty() || kind !in KINDS) return null
        return BackupCategory(id!!.toLong(), n, kind!!, builtIn ?: false)
    }

    private fun readRate(r: JsonReader): BackupRate? {
        var kind: String? = null
        var from: String? = null
        var dpm: Double? = null
        if (!obj(r) {
                when (it) {
                    "kind" -> kind = str(r)
                    "effectiveFrom" -> from = str(r)
                    "dollarsPerMile" -> dpm = num(r)
                    else -> r.skipValue()
                }
            }
        ) return null
        val day = from?.let { Validation.parseDay(it) } ?: return null
        val value = dpm ?: return null
        if (kind !in KINDS || kind == "PERSONAL" || !Validation.isValidRate(value)) return null
        return BackupRate(kind!!, day.toString(), value)
    }

    private fun readPlace(r: JsonReader): BackupPlace? {
        var name: String? = null
        var lat: Double? = null
        var lng: Double? = null
        var radius: Double? = null
        var cat: Double? = null
        var ignore = false
        if (!obj(r) {
                when (it) {
                    "name" -> name = str(r)
                    "lat" -> lat = num(r)
                    "lng" -> lng = num(r)
                    "radiusMeters" -> radius = num(r)
                    "categoryId" -> cat = num(r)
                    "ignoreDrives" -> ignore = bool(r) ?: false
                    else -> r.skipValue()
                }
            }
        ) return null
        val n = name?.trim()?.take(MAX_NAME)
        val la = lat ?: return null
        val ln = lng ?: return null
        val rad = radius ?: 150.0
        if (n.isNullOrEmpty() || la !in -90.0..90.0 || ln !in -180.0..180.0 || !rad.isFinite() || rad < 25.0 || rad > 5_000.0) return null
        return BackupPlace(n, la, ln, rad, cat?.toLong(), ignore)
    }

    private fun readWorkHours(r: JsonReader): WorkHours? {
        var enabled = false
        var days: String? = null
        var start: Double? = null
        var end: Double? = null
        var cat: Double? = null
        if (!obj(r) {
                when (it) {
                    "enabled" -> enabled = bool(r) ?: false
                    "days" -> days = str(r)
                    "startMin" -> start = num(r)
                    "endMin" -> end = num(r)
                    "categoryId" -> cat = num(r)
                    else -> r.skipValue()
                }
            }
        ) return null
        val s = start?.toInt() ?: return null
        val e = end?.toInt() ?: return null
        if (s !in 0..1439 || e !in 0..1439) return null
        return WorkHours(enabled, WorkHours.parseDays(days), s, e, cat?.toLong())
    }

    private fun readOdometer(r: JsonReader): BackupOdometer? {
        var year: Double? = null
        var start: Double? = null
        var end: Double? = null
        if (!obj(r) {
                when (it) {
                    "year" -> year = num(r)
                    "startMiles" -> start = num(r)
                    "endMiles" -> end = num(r)
                    else -> r.skipValue()
                }
            }
        ) return null
        val y = year?.toInt() ?: return null
        if (y !in 1990..2200) return null
        if (start != null && !Validation.isValidOdometer(start)) return null
        if (end != null && !Validation.isValidOdometer(end)) return null
        return BackupOdometer(y, start, end)
    }

    private fun readTrip(r: JsonReader): BackupTrip? {
        var start: Double? = null
        var end: Double? = null
        var meters: Double? = null
        var cat: Double? = null
        var notes = ""
        var route = ""
        var source = "gps"
        var edited = false
        var parking: Double? = null
        var tolls: Double? = null
        var tooLong = false
        if (!obj(r) {
                when (it) {
                    "startTime" -> start = num(r)
                    "endTime" -> end = num(r)
                    "distanceMeters" -> meters = num(r)
                    "categoryId" -> cat = num(r)
                    "notes" -> notes = str(r)?.take(MAX_NOTES) ?: ""
                    "route" -> {
                        val text = str(r) ?: ""
                        if (text.length > MAX_ROUTE) tooLong = true else route = text
                    }
                    "source" -> source = str(r) ?: "gps"
                    "edited" -> edited = bool(r) ?: false
                    "parkingCents" -> parking = num(r)
                    "tollsCents" -> tolls = num(r)
                    else -> r.skipValue()
                }
            }
        ) return null
        val s = start?.toLong() ?: return null
        val e = end?.toLong() ?: return null
        val m = meters ?: return null
        if (tooLong || s <= 0 || e < s || !m.isFinite() || m < 0.0 || m > MAX_METERS) return null
        if (source !in SOURCES) return null
        val p = parking?.toLong() ?: 0L
        val tl = tolls?.toLong() ?: 0L
        if (p !in 0..Validation.MAX_EXPENSE_CENTS || tl !in 0..Validation.MAX_EXPENSE_CENTS) return null
        return BackupTrip(s, e, m, cat?.toLong(), notes, route, source, edited, p, tl)
    }
}

/** Decides what a restore adds. It never removes or changes anything already on the phone. */
object BackupMerge {
    fun key(name: String, kind: String) = name.trim().lowercase() + "|" + kind

    /** Categories in the backup that this phone does not have yet (matched by name and kind). */
    fun missingCategories(backup: List<BackupCategory>, existingKeys: Set<String>): List<BackupCategory> =
        backup.filter { key(it.name, it.kind) !in existingKeys }.distinctBy { key(it.name, it.kind) }

    /** Rates in the backup that this phone does not have (matched by kind and start date). */
    fun missingRates(backup: List<BackupRate>, existing: Set<Pair<String, String>>): List<BackupRate> =
        backup.filter { (it.kind to it.effectiveFrom) !in existing }.distinctBy { it.kind to it.effectiveFrom }

    /** Places match when the name and the spot (to about 10 m) are the same. */
    fun placeKey(name: String, lat: Double, lng: Double) =
        name.trim().lowercase() + "|" + String.format(java.util.Locale.US, "%.4f,%.4f", lat, lng)

    /** A drive is already here when one with the same start and end time exists. */
    fun spanKey(start: Long, end: Long) = start to end
}

/** When to suggest making a backup. Works fully offline. */
object BackupReminder {
    const val DAY_MS = 86_400_000L
    const val EVERY_DAYS = 30L
    const val SNOOZE_DAYS = 14L
    const val MIN_DRIVES = 20

    fun shouldShow(nowMs: Long, lastBackupMs: Long, drives: Int, snoozedUntilMs: Long): Boolean =
        drives >= MIN_DRIVES && nowMs >= snoozedUntilMs &&
            (lastBackupMs <= 0L || nowMs - lastBackupMs >= EVERY_DAYS * DAY_MS)
}
