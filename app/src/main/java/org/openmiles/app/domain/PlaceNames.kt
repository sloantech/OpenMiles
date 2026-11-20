package org.openmiles.app.domain

import kotlin.math.cos

/**
 * Naming places with a tap instead of typing, and showing those names on drives.
 * Everything here works on the phone with no internet. No Android code, so it can be tested.
 */
object PlaceNames {
    /** Spots visited this many times get a "Name this spot?" card. */
    const val MIN_VISITS = 3

    /** How close two drive ends must be to count as the same spot. Matches a place's size. */
    const val SPOT_RADIUS_M = 150.0

    /** Characters read from each end of a stored route. One point is about 35 characters. */
    const val END_CHARS = 120

    /**
     * Names to tap. Some fill in a matching auto-classify rule ([kind] is a category kind, like
     * "BUSINESS"), which the person sees and can change before saving.
     */
    data class Preset(val name: String, val kind: String?)

    val PRESETS = listOf(
        Preset("Home", null),
        Preset("Work", "BUSINESS"),
        Preset("Office", "BUSINESS"),
        Preset("Client", "BUSINESS"),
        Preset("Store", null),
        Preset("Bank", null),
        Preset("Gas station", null),
        Preset("Restaurant", null),
        Preset("Gym", null),
        Preset("Church", null),
        Preset("School", null),
        Preset("Doctor", "MEDICAL")
    )

    data class LatLng(val lat: Double, val lng: Double)

    /** Where one drive started and ended. */
    data class Ends(val tripId: Long, val startTime: Long, val start: LatLng, val end: LatLng)

    /** A spot drives keep starting or ending at, with no name yet. */
    data class Spot(val center: LatLng, val visits: Int, val lastTripId: Long, val lastTime: Long, val lastWasEnd: Boolean)

    /**
     * Reads the first and last point of a route from just its two ends, so years of drives can be
     * labeled without loading every full route. [head] is the start of the stored route and
     * [tail] is its end. Routes are stored in time order.
     */
    fun endsFrom(head: String?, tail: String?): Pair<LatLng, LatLng>? {
        if (head.isNullOrBlank() || tail.isNullOrBlank()) return null
        val first = head.split(";").firstNotNullOfOrNull { point(it) } ?: return null
        val last = tail.split(";").reversed().firstNotNullOfOrNull { point(it) } ?: return null
        return first to last
    }

    private fun point(text: String): LatLng? {
        val p = TripEdit.parseRoute(text).firstOrNull() ?: return null
        return LatLng(p.lat, p.lng)
    }

    /** The named place at a point, or null. The closest one wins when places overlap. */
    fun nameAt(places: List<PlaceRule>, at: LatLng?): String? =
        at?.let { AutoClassify.placeAt(places, it.lat, it.lng)?.name }

    /** Short label for the drive list, like "Home to Bank". Null when neither end is named. */
    fun label(startName: String?, endName: String?): String? = when {
        startName != null && endName != null -> "$startName to $endName"
        startName != null -> "From $startName"
        endName != null -> "To $endName"
        else -> null
    }

    /**
     * Finds spots that drives have started or ended at [MIN_VISITS] times or more, that are not
     * named yet and that the person didn't wave off. The busiest spot comes first.
     */
    fun unnamedSpots(drives: List<Ends>, places: List<PlaceRule>, dismissed: List<LatLng>): List<Spot> {
        class Group(var lat: Double, var lng: Double) {
            var count = 0
            var lastTime = Long.MIN_VALUE
            var lastTripId = 0L
            var lastWasEnd = false
            fun add(at: LatLng, tripId: Long, time: Long, isEnd: Boolean) {
                // Keep the center as the average of everything added.
                lat = (lat * count + at.lat) / (count + 1)
                lng = (lng * count + at.lng) / (count + 1)
                count++
                if (time >= lastTime) {
                    lastTime = time
                    lastTripId = tripId
                    lastWasEnd = isEnd
                }
            }
        }
        val groups = mutableListOf<Group>()
        fun addPoint(at: LatLng, tripId: Long, time: Long, isEnd: Boolean) {
            if (!at.lat.isFinite() || !at.lng.isFinite()) return
            if (AutoClassify.placeAt(places, at.lat, at.lng) != null) return
            val g = groups.firstOrNull { near(it.lat, it.lng, at) } ?: Group(at.lat, at.lng).also { groups.add(it) }
            g.add(at, tripId, time, isEnd)
        }
        drives.sortedBy { it.startTime }.forEach { d ->
            addPoint(d.start, d.tripId, d.startTime, false)
            // A drive that starts and ends at the same spot counts once there.
            if (!near(d.start.lat, d.start.lng, d.end)) addPoint(d.end, d.tripId, d.startTime, true)
        }
        return groups
            .filter { it.count >= MIN_VISITS }
            .map { Spot(LatLng(it.lat, it.lng), it.count, it.lastTripId, it.lastTime, it.lastWasEnd) }
            .filter { s -> dismissed.none { near(it.lat, it.lng, s.center) } }
            .sortedWith(compareByDescending<Spot> { it.visits }.thenByDescending { it.lastTime })
    }

    /** A quick distance check that is accurate enough at this small size. */
    private fun near(lat: Double, lng: Double, other: LatLng): Boolean {
        val dLat = (other.lat - lat) * METERS_PER_DEGREE
        val dLng = (other.lng - lng) * METERS_PER_DEGREE * cos(Math.toRadians(lat))
        return dLat * dLat + dLng * dLng <= SPOT_RADIUS_M * SPOT_RADIUS_M
    }

    /** Saved as "lat,lng;lat,lng". Damaged parts are skipped. At most [MAX_DISMISSED] are kept. */
    fun encodeDismissed(list: List<LatLng>): String =
        list.takeLast(MAX_DISMISSED).joinToString(";") { String.format(java.util.Locale.US, "%.5f,%.5f", it.lat, it.lng) }

    fun decodeDismissed(text: String?): List<LatLng> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(";").mapNotNull { part ->
            val bits = part.split(",")
            if (bits.size != 2) return@mapNotNull null
            val lat = bits[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lng = bits[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            LatLng(lat, lng)
        }.takeLast(MAX_DISMISSED)
    }

    const val MAX_DISMISSED = 200
    private const val METERS_PER_DEGREE = 111_195.0
}
