package org.openmiles.app.domain

/**
 * Decides when an automatically started drive is over. It holds no Android code, so it can be tested.
 *
 *  - Parked: the phone has stayed within 100 m of one spot for 5 minutes. The drive ends at the
 *    moment it stopped moving, not 5 minutes later.
 *  - Not a drive: 10 minutes have passed and the phone never went faster than 15 mph. This catches
 *    walks that set off the parking fence. Those are thrown away.
 */
class AutoTripMonitor(private val startMs: Long) {
    sealed class Decision {
        object KeepGoing : Decision()
        object NotADrive : Decision()
        data class Parked(val endMs: Long) : Decision()
    }

    private var anchorLat: Double? = null
    private var anchorLng: Double = 0.0
    private var prev: TripRecorder.Fix? = null

    var lastMovedMs: Long = startMs
        private set

    var maxSpeedMps: Double = 0.0
        private set

    /** Feed every fix that is accurate enough. [speedMps] is the phone's own speed, if it gave one. */
    fun onFix(lat: Double, lng: Double, timeMs: Long, speedMps: Float?) {
        val aLat = anchorLat
        if (aLat == null) {
            anchorLat = lat
            anchorLng = lng
            lastMovedMs = maxOf(lastMovedMs, timeMs)
        } else if (Geo.distanceMeters(aLat, anchorLng, lat, lng) > MOVE_RADIUS_M) {
            anchorLat = lat
            anchorLng = lng
            lastMovedMs = maxOf(lastMovedMs, timeMs)
        }

        val reported = speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_SPEED_MPS }
        val p = prev
        val derived = if (p != null) {
            val seconds = (timeMs - p.timeMs) / 1000.0
            val meters = Geo.distanceMeters(p.lat, p.lng, lat, lng)
            // Short gaps make GPS wobble look like speed, so only trust longer, larger steps.
            if (seconds >= MIN_SPEED_SECONDS && meters >= MIN_SPEED_METERS) {
                (meters / seconds).takeIf { it <= MAX_SPEED_MPS }
            } else {
                null
            }
        } else {
            null
        }
        val speed = reported ?: derived
        if (speed != null && speed > maxSpeedMps) maxSpeedMps = speed
        prev = TripRecorder.Fix(lat, lng, timeMs, null)
    }

    fun check(nowMs: Long): Decision {
        if (maxSpeedMps < DRIVE_SPEED_MPS && nowMs - startMs >= NEVER_DROVE_MS) return Decision.NotADrive
        if (nowMs - lastMovedMs >= STATIONARY_MS) return Decision.Parked(lastMovedMs)
        return Decision.KeepGoing
    }

    companion object {
        const val MOVE_RADIUS_M = 100.0
        const val STATIONARY_MS = 5 * 60_000L
        const val NEVER_DROVE_MS = 10 * 60_000L
        /** 15 mph. Walking is about 3 mph. */
        const val DRIVE_SPEED_MPS = 6.7
        const val MAX_SPEED_MPS = 70.0
        const val MIN_SPEED_SECONDS = 5.0
        const val MIN_SPEED_METERS = 20.0
        /** Automatic drives shorter than this (about a quarter mile) are thrown away. */
        const val MIN_AUTO_TRIP_M = 400.0
    }
}

/**
 * A drive in progress, saved to the phone every 30 seconds. If Android closes the app in the
 * middle of a drive, the drive is saved from this the next time the app or recording starts.
 */
data class Draft(
    val startMs: Long,
    val endMs: Long,
    val meters: Double,
    val route: String,
    val auto: Boolean,
    val maxSpeedMps: Double
) {
    /** Manual drives keep the old rule. Automatic ones must be long enough and really be a drive. */
    fun worthKeeping(): Boolean =
        if (auto) {
            meters >= AutoTripMonitor.MIN_AUTO_TRIP_M && maxSpeedMps >= AutoTripMonitor.DRIVE_SPEED_MPS
        } else {
            meters >= TripRecorder.MIN_TRIP_M
        }

    fun encode(): String =
        listOf(VERSION, startMs, endMs, meters, auto, maxSpeedMps, route).joinToString("\n")

    companion object {
        private const val VERSION = "v1"

        /** Reads a saved draft. Anything damaged or odd gives null instead of bad data. */
        fun decode(text: String): Draft? = runCatching {
            val parts = text.split("\n", limit = 7)
            require(parts.size == 7 && parts[0] == VERSION)
            val draft = Draft(
                startMs = parts[1].toLong(),
                endMs = parts[2].toLong(),
                meters = parts[3].toDouble(),
                auto = parts[4].toBooleanStrict(),
                maxSpeedMps = parts[5].toDouble(),
                route = parts[6]
            )
            require(draft.startMs > 0 && draft.endMs >= draft.startMs)
            require(draft.meters.isFinite() && draft.meters >= 0.0)
            require(draft.maxSpeedMps.isFinite() && draft.maxSpeedMps >= 0.0)
            require(!draft.route.contains('\n'))
            draft
        }.getOrNull()
    }
}
