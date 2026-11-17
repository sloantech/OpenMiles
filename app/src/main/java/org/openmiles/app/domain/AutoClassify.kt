package org.openmiles.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** A place the person named, with an optional rule. Mirrors the database row, minus Android. */
data class PlaceRule(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    /** Drives that start or end here get this category. Null: no category rule. */
    val categoryId: Long?,
    /** Drives that start and end here are not recorded. */
    val ignoreDrives: Boolean
)

/** "Drives that start during these hours on these days get this category." */
data class WorkHours(
    val enabled: Boolean = false,
    val days: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    ),
    val startMin: Int = 8 * 60,
    val endMin: Int = 17 * 60,
    val categoryId: Long? = null
) {
    fun covers(timeMs: Long, zone: ZoneId): Boolean {
        if (!enabled || categoryId == null || days.isEmpty() || startMin == endMin) return false
        val t = Instant.ofEpochMilli(timeMs).atZone(zone)
        val minute = t.hour * 60 + t.minute
        return if (startMin < endMin) {
            t.dayOfWeek in days && minute >= startMin && minute < endMin
        } else {
            // An overnight shift, such as 10 PM to 6 AM: the early part belongs to the day before.
            (t.dayOfWeek in days && minute >= startMin) ||
                (t.dayOfWeek.minus(1) in days && minute < endMin)
        }
    }

    /** Stored as "1,2,3,4,5" (Monday = 1). */
    fun daysText(): String = days.map { it.value }.sorted().joinToString(",")

    companion object {
        fun parseDays(text: String?): Set<DayOfWeek> =
            text.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
    }
}

/**
 * Picks a category for a new drive. Place rules come first (where the drive ends, then where
 * it starts), then work hours. A rule pointing at a category that no longer exists is ignored.
 * Returns null when nothing applies, which leaves the drive for the person to classify.
 */
object AutoClassify {
    fun inside(place: PlaceRule, lat: Double, lng: Double): Boolean =
        Geo.distanceMeters(place.lat, place.lng, lat, lng) <= place.radiusMeters

    /** The closest place that contains the point, if any. */
    fun placeAt(places: List<PlaceRule>, lat: Double?, lng: Double?): PlaceRule? {
        if (lat == null || lng == null) return null
        return places.filter { inside(it, lat, lng) }
            .minByOrNull { Geo.distanceMeters(it.lat, it.lng, lat, lng) }
    }

    fun categoryFor(
        start: Pair<Double, Double>?,
        end: Pair<Double, Double>?,
        startMs: Long,
        places: List<PlaceRule>,
        work: WorkHours,
        existingCategoryIds: Set<Long>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val valid = { id: Long? -> id != null && id in existingCategoryIds }
        placeAt(places, end?.first, end?.second)?.categoryId?.let { if (valid(it)) return it }
        placeAt(places, start?.first, start?.second)?.categoryId?.let { if (valid(it)) return it }
        if (work.covers(startMs, zone) && valid(work.categoryId)) return work.categoryId
        return null
    }

    /** True when both ends of a drive are inside the same place marked "ignore drives here". */
    fun ignored(start: Pair<Double, Double>?, end: Pair<Double, Double>?, places: List<PlaceRule>): Boolean {
        if (start == null || end == null) return false
        return places.any { it.ignoreDrives && inside(it, start.first, start.second) && inside(it, end.first, end.second) }
    }

    /** First and last point of a stored route, or null when it has none. */
    fun ends(route: String): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
        val points = TripEdit.parseRoute(route)
        if (points.isEmpty()) return null
        val first = points.minByOrNull { it.timeMs }!!
        val last = points.maxByOrNull { it.timeMs }!!
        return (first.lat to first.lng) to (last.lat to last.lng)
    }
}
