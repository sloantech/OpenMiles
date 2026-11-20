package org.openmiles.app.domain

import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

/**
 * The math behind the drive map. It draws the GPS route the phone saved, with no map pictures,
 * so the app never needs the internet. No Android code, so it can be tested.
 */
object RouteMap {
    /** The most points drawn on screen. Long drives are thinned evenly, keeping the ends. */
    const val MAX_DRAW_POINTS = 600

    /** Google Maps accepts up to 9 stops between start and end when it opens in the Maps app. */
    const val MAX_WAYPOINTS = 8

    data class Pt(val x: Float, val y: Float)

    /** The saved route in time order, with damaged points skipped. */
    fun points(route: String): List<TripEdit.RoutePoint> =
        TripEdit.parseRoute(route).sortedBy { it.timeMs }

    /**
     * Keeps at most [max] points, spread evenly along the list, always keeping the first and last.
     */
    fun thin(points: List<TripEdit.RoutePoint>, max: Int): List<TripEdit.RoutePoint> {
        if (max < 2 || points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { i -> points[Math.round(i * step).toInt().coerceIn(0, points.size - 1)] }
    }

    /**
     * Turns GPS points into screen positions inside a [width] by [height] box, leaving [pad] on
     * every side. North is up. East-west distances are shrunk by the latitude so the shape of the
     * drive is not stretched. The route is centered, and a drive that barely moved is shown as a
     * small cluster in the middle instead of being blown up to fill the box.
     */
    fun project(points: List<TripEdit.RoutePoint>, width: Float, height: Float, pad: Float): List<Pt> {
        if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val midLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
        val k = cos(Math.toRadians(midLat)).coerceAtLeast(0.01)
        // x and y in "degrees of latitude", so both directions use the same scale.
        val xs = points.map { it.lng * k }
        val ys = points.map { it.lat }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        // At least about 200 meters across, so GPS wobble in a parking lot stays small.
        val span = max(max(maxX - minX, maxY - minY), MIN_SPAN_DEGREES)
        val boxW = (width - 2 * pad).coerceAtLeast(1f)
        val boxH = (height - 2 * pad).coerceAtLeast(1f)
        val scale = minOf(boxW, boxH) / span
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        return xs.indices.map { i ->
            Pt(
                (width / 2 + (xs[i] - cx) * scale).toFloat(),
                (height / 2 - (ys[i] - cy) * scale).toFloat()
            )
        }
    }

    /** Straight-line size of the drive's area, for the "about X miles across" label. */
    fun widthMeters(points: List<TripEdit.RoutePoint>): Double {
        if (points.size < 2) return 0.0
        val south = points.minOf { it.lat }
        val north = points.maxOf { it.lat }
        val west = points.minOf { it.lng }
        val east = points.maxOf { it.lng }
        val mid = (south + north) / 2
        val ew = Geo.distanceMeters(mid, west, mid, east)
        val ns = Geo.distanceMeters(south, west, north, west)
        return max(ew, ns)
    }

    /**
     * A Google Maps link that shows this drive's start, end and a few points along the way.
     * It only shows a route preview. It never starts turn-by-turn navigation.
     * Returns null when the drive has no GPS route.
     */
    fun mapsLink(points: List<TripEdit.RoutePoint>): String? {
        if (points.isEmpty()) return null
        val start = points.first()
        val end = points.last()
        val middle = if (points.size > 2) points.subList(1, points.size - 1) else emptyList()
        val stops = thin(middle, MAX_WAYPOINTS + 2).let {
            // thin() keeps the ends of the middle part. Drop them when there are many points,
            // so the stops are spread out instead of sitting right next to start and end.
            if (it.size > MAX_WAYPOINTS) it.subList(1, it.size - 1) else it
        }.take(MAX_WAYPOINTS)
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
        sb.append("&origin=").append(latLng(start))
        sb.append("&destination=").append(latLng(end))
        if (stops.isNotEmpty()) sb.append("&waypoints=").append(stops.joinToString("%7C") { latLng(it) })
        sb.append("&travelmode=driving")
        return sb.toString()
    }

    private fun latLng(p: TripEdit.RoutePoint): String =
        String.format(Locale.US, "%.5f%%2C%.5f", p.lat, p.lng)

    private const val MIN_SPAN_DEGREES = 0.0018 // about 200 meters
}
