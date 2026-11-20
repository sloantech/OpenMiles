package org.openmiles.app.domain

import java.util.Locale

/**
 * The rules for starting and ending drives with the car's Bluetooth. No Android code, so it can
 * be tested.
 *
 * Connecting to a chosen car starts a drive at once. Disconnecting does not end it at once,
 * because car Bluetooth can drop for a few seconds while driving. The drive ends only after the
 * car has stayed disconnected for [GRACE_MS], and it ends at the moment it disconnected.
 */
object CarLink {
    const val GRACE_MS = 60_000L

    /** Most cars a person can pick. */
    const val MAX_CARS = 5

    data class Car(val address: String, val name: String)

    private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    fun isAddress(text: String): Boolean = MAC.matches(text)

    /** True once the car has been disconnected long enough to call the drive over. */
    fun shouldEnd(disconnectedAtMs: Long?, nowMs: Long): Boolean =
        disconnectedAtMs != null && nowMs - disconnectedAtMs >= GRACE_MS

    /** Saved as one car per line: address, a tab, then the name. */
    fun encode(cars: List<Car>): String =
        cars.take(MAX_CARS).joinToString("\n") { "${it.address.uppercase(Locale.US)}\t${clean(it.name)}" }

    /** Reads the saved list. Damaged lines are skipped, and each car appears only once. */
    fun decode(text: String?): List<Car> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split("\n").mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            val address = parts[0].trim().uppercase(Locale.US)
            if (!isAddress(address)) return@mapNotNull null
            Car(address, clean(parts.getOrElse(1) { "" }).ifBlank { "Car" })
        }.distinctBy { it.address }.take(MAX_CARS)
    }

    /** Names come from other devices, so keep them short and on one line. */
    fun clean(name: String): String =
        name.replace(Regex("[\\t\\r\\n\\p{Cntrl}]"), " ").trim().take(40)
}

/** What the app says out loud. Short, plain sentences that are easy to hear over road noise. */
object Speech {
    fun started(): String = "Drive started."

    fun miles(meters: Double): String {
        val m = meters / Calc.METERS_PER_MILE
        // "5 miles" is easier to hear than "five point zero miles".
        val text = String.format(Locale.US, "%.1f", m).removeSuffix(".0")
        return if (text == "1") "1 mile" else "$text miles"
    }

    /**
     * Said when a drive is saved. [categoryName] is set when a rule already classified it.
     * [asking] is true when the drive-ended notification has Business and Personal buttons.
     */
    fun saved(meters: Double, categoryName: String?, asking: Boolean): String {
        val base = "Drive saved. ${miles(meters)}."
        return when {
            categoryName != null -> "$base Marked ${clean(categoryName)}."
            asking -> "$base Pick business or personal in the notification when you're parked."
            else -> base
        }
    }

    /** Category names are typed by the person, so keep them speakable. */
    private fun clean(name: String): String = CarLink.clean(name).take(30)
}
