package org.openmiles.app.domain

import java.time.Instant
import java.time.ZoneId

/** Choices for pausing automatic detection, and when each one ends. */
object Pause {
    const val FOREVER = Long.MAX_VALUE

    enum class Option(val label: String) {
        HOUR("For 1 hour"),
        TODAY("For the rest of today"),
        DAY("For 24 hours"),
        WEEK("For 1 week"),
        UNTIL_RESUMED("Until I turn it back on")
    }

    fun until(option: Option, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (option) {
        Option.HOUR -> nowMs + 3_600_000L
        Option.TODAY -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        Option.DAY -> nowMs + 86_400_000L
        Option.WEEK -> nowMs + 7 * 86_400_000L
        Option.UNTIL_RESUMED -> FOREVER
    }

    fun isPaused(nowMs: Long, pausedUntilMs: Long): Boolean = pausedUntilMs > nowMs
}
