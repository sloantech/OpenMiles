package org.openmiles.app.domain

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Straight-line distance between two GPS points, in meters (haversine). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * Turns a stream of GPS fixes into a distance. It holds no Android code, so it can be tested.
 *
 * It ignores fixes that are likely wrong:
 *  - the phone says the fix is inaccurate (more than 50 m),
 *  - the fix is older than or the same age as the last one,
 *  - the phone barely moved (under 10 m), which is mostly GPS wobble while parked,
 *  - the jump would need a speed over 70 m/s (about 157 mph), which is a GPS glitch.
 */
class TripRecorder {
    data class Fix(
        val lat: Double,
        val lng: Double,
        val timeMs: Long,
        /** Null when the phone did not report accuracy. */
        val accuracyM: Float?
    )

    private val points = mutableListOf<Fix>()
    private var last: Fix? = null

    var meters: Double = 0.0
        private set

    val pointCount: Int get() = points.size

    /** Returns true if the fix was used. */
    fun add(fix: Fix): Boolean {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return false

        val prev = last
        if (prev == null) {
            accept(fix)
            return true
        }

        val seconds = (fix.timeMs - prev.timeMs) / 1000.0
        if (seconds <= 0.0) return false

        val step = Geo.distanceMeters(prev.lat, prev.lng, fix.lat, fix.lng)
        if (step < MIN_STEP_M) return false
        if (step / seconds > MAX_SPEED_MPS) return false

        meters += step
        accept(fix)
        return true
    }

    private fun accept(fix: Fix) {
        points.add(fix)
        last = fix
    }

    /** The route as "lat,lng,time;lat,lng,time;..." */
    fun routeString(): String = points.joinToString(";") {
        String.format(Locale.US, "%.5f,%.5f,%d", it.lat, it.lng, it.timeMs)
    }

    fun reset() {
        points.clear()
        last = null
        meters = 0.0
    }

    companion object {
        const val MAX_ACCURACY_M = 50f
        const val MIN_STEP_M = 10.0
        const val MAX_SPEED_MPS = 70.0

        /** Drives shorter than this are thrown away. */
        const val MIN_TRIP_M = 50.0
    }
}
