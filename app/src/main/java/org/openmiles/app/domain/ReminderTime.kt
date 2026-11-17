package org.openmiles.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** When the weekly "drives to classify" reminder fires, and what it says. */
object ReminderTime {
    /** Sunday at 6 PM, a calm time to catch up before the week starts. */
    val DAY: DayOfWeek = DayOfWeek.SUNDAY
    const val HOUR = 18

    /** The next Sunday 6 PM strictly after [nowMs]. */
    fun next(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var at = now.with(TemporalAdjusters.nextOrSame(DAY)).withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusWeeks(1)
        return at.toInstant().toEpochMilli()
    }

    fun text(unclassified: Int): String? = when {
        unclassified <= 0 -> null
        unclassified == 1 -> "1 drive is waiting for a category."
        else -> "$unclassified drives are waiting for a category."
    }
}
