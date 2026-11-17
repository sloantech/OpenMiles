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
    val source: String
)

/** Everything in a backup except the drives, which are handed over one at a time. */
data class BackupInfo(
    val formatVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val categories: List<BackupCategory>,
    val rates: List<BackupRate>,
    val trips: Int,
    val rejected: Int
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

    class NotABackup(message: String) : Exception(message)

    /** Writes a backup. [eachTrip] is called once and hands every drive to [emit]. */
    fun write(
        out: Writer,
        appVersion: String,
        createdAt: Long,
        categories: List<BackupCategory>,
        rates: List<BackupRate>,
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
        return BackupInfo(version, appVersion, createdAt, categories, rates, trips, rejected)
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

    private fun readTrip(r: JsonReader): BackupTrip? {
        var start: Double? = null
        var end: Double? = null
        var meters: Double? = null
        var cat: Double? = null
        var notes = ""
        var route = ""
        var source = "gps"
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
                    else -> r.skipValue()
                }
            }
        ) return null
        val s = start?.toLong() ?: return null
        val e = end?.toLong() ?: return null
        val m = meters ?: return null
        if (tooLong || s <= 0 || e < s || !m.isFinite() || m < 0.0 || m > MAX_METERS) return null
        if (source !in SOURCES) return null
        return BackupTrip(s, e, m, cat?.toLong(), notes, route, source)
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
