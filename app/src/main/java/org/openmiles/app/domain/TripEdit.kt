package org.openmiles.app.domain

import java.util.Locale

/** The math behind splitting and merging drives. No Android code, so it can be tested. */
object TripEdit {
    data class RoutePoint(val lat: Double, val lng: Double, val timeMs: Long)

    data class Split(
        val firstMeters: Double,
        val secondMeters: Double,
        val firstRoute: String,
        val secondRoute: String
    )

    /** Reads "lat,lng,time;lat,lng,time". Damaged points are skipped. */
    fun parseRoute(route: String): List<RoutePoint> =
        if (route.isBlank()) emptyList() else route.split(";").mapNotNull { part ->
            val f = part.split(",")
            if (f.size != 3) return@mapNotNull null
            val lat = f[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val t = f[2].toLongOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else RoutePoint(lat, lng, t)
        }

    fun formatRoute(points: List<RoutePoint>): String =
        points.joinToString(";") { String.format(Locale.US, "%.5f,%.5f,%d", it.lat, it.lng, it.timeMs) }

    /**
     * Splits a drive at [atMs]. The miles are divided by the GPS route when there is one (the part
     * driven before the split time), otherwise by time. Returns null unless the split time is
     * strictly inside the drive.
     */
    fun split(startMs: Long, endMs: Long, meters: Double, route: String, atMs: Long): Split? {
        if (atMs <= startMs || atMs >= endMs) return null
        val points = parseRoute(route).sortedBy { it.timeMs }
        val before = points.filter { it.timeMs <= atMs }
        val after = points.filter { it.timeMs > atMs }

        var fraction = (atMs - startMs).toDouble() / (endMs - startMs)
        if (points.size >= 2) {
            var total = 0.0
            var firstPart = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val step = Geo.distanceMeters(a.lat, a.lng, b.lat, b.lng)
                total += step
                when {
                    b.timeMs <= atMs -> firstPart += step
                    a.timeMs < atMs && b.timeMs > a.timeMs ->
                        firstPart += step * (atMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)
                }
            }
            if (total > 0.0) fraction = firstPart / total
        }
        fraction = fraction.coerceIn(0.0, 1.0)
        return Split(meters * fraction, meters * (1 - fraction), formatRoute(before), formatRoute(after))
    }

    fun mergeRoutes(first: String, second: String): String =
        listOf(first, second).filter { it.isNotBlank() }.joinToString(";")

    fun mergeNotes(first: String, second: String, max: Int = 200): String =
        listOf(first.trim(), second.trim()).filter { it.isNotEmpty() }.distinct().joinToString("; ").take(max)

    /**
     * Turns a date's start-of-day plus start and end minutes into times. An end time earlier
     * than the start time means the drive ended the next day.
     */
    fun times(dayStartMs: Long, startMin: Int, endMin: Int, nextDayStartMs: Long): Pair<Long, Long>? {
        if (startMin == endMin) return null
        val start = dayStartMs + startMin * 60_000L
        val end = if (endMin > startMin) dayStartMs + endMin * 60_000L else nextDayStartMs + endMin * 60_000L
        return start to end
    }
}
