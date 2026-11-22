package org.openmiles.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** When the weekly and monthly summaries fire. Everything here is plain math, easy to test. */
object ReminderTime {
    /** The default: Sunday at 6 PM, a calm time to catch up before the week starts. */
    val DAY: DayOfWeek = DayOfWeek.SUNDAY
    const val HOUR = 18

    /** The monthly summary shows on the 1st at this hour. */
    const val MONTH_HOUR = 9

    /** Hours a person can pick for the weekly summary. */
    val HOURS: List<Int> = (6..21).toList()

    /** The next [day] at [hour]:00 strictly after [nowMs]. */
    fun next(
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        day: DayOfWeek = DAY,
        hour: Int = HOUR
    ): Long {
        val h = hour.coerceIn(0, 23)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var at = now.with(TemporalAdjusters.nextOrSame(day)).withHour(h).withMinute(0).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusWeeks(1)
        return at.toInstant().toEpochMilli()
    }

    /** The next 1st of a month at [hour]:00 strictly after [nowMs]. */
    fun nextMonthStart(nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), hour: Int = MONTH_HOUR): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var at = now.withDayOfMonth(1).withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusMonths(1)
        return at.toInstant().toEpochMilli()
    }

    /** The calendar month before the one [nowMs] falls in. */
    fun previousMonth(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): DateRange {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return DateRange.lastMonth(today)
    }

    /** Start and end (exclusive) of a date range, in milliseconds, for database queries. */
    fun bounds(range: DateRange, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        range.from.atStartOfDay(zone).toInstant().toEpochMilli() to
            range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayLabel(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.US)

    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12 AM"
        hour < 12 -> "$hour AM"
        hour == 12 -> "12 PM"
        else -> "${hour - 12} PM"
    }

    /** Text for the old count-only reminder. Still used when nothing else is worth saying. */
    fun text(unclassified: Int): String? = when {
        unclassified <= 0 -> null
        unclassified == 1 -> "1 drive is waiting for a category."
        else -> "$unclassified drives are waiting for a category."
    }
}

/** The numbers in a weekly or monthly summary. */
data class PeriodSummary(
    val drives: Int,
    val meters: Double,
    val businessMeters: Double,
    /** Dollar value of business, medical and charity drives at your rates. */
    val value: Double,
    /** Drives in this period that still need a category. */
    val unclassified: Int
)

object Summaries {
    /**
     * Adds up drives. [kindOf] gives the tax kind for a category id, or null when the drive has
     * no category (or its category was deleted). [rateOf] gives dollars per mile for a kind on a date.
     */
    fun build(
        trips: List<Triple<Long?, Double, LocalDate>>,
        kindOf: (Long?) -> String?,
        rateOf: (String, LocalDate) -> Double
    ): PeriodSummary {
        var meters = 0.0
        var business = 0.0
        var value = 0.0
        var unclassified = 0
        for ((categoryId, m, date) in trips) {
            val safe = if (m.isFinite() && m > 0) m else 0.0
            meters += safe
            val kind = kindOf(categoryId)
            if (kind == null) {
                unclassified++
                continue
            }
            if (kind == "BUSINESS") business += safe
            value += Calc.miles(safe) * rateOf(kind, date)
        }
        return PeriodSummary(trips.size, meters, business, value, unclassified)
    }

    private fun drives(n: Int) = if (n == 1) "1 drive" else "$n drives"

    /**
     * The weekly note. [waitingTotal] counts every unclassified drive, not just this week's.
     * Returns null when there is nothing worth saying, so no notification is shown.
     */
    fun weeklyText(s: PeriodSummary, waitingTotal: Int): String? {
        if (s.drives == 0) return ReminderTime.text(waitingTotal)
        val parts = mutableListOf("Past 7 days: ${drives(s.drives)}, ${Calc.fmtMiles(s.meters)}.")
        if (s.businessMeters > 0) parts += "Business ${Calc.fmtMiles(s.businessMeters)}."
        if (s.value > 0.005) parts += "Worth ${Calc.fmtMoney(s.value)}."
        waitingLine(waitingTotal)?.let { parts += it }
        return parts.joinToString(" ")
    }

    /** The monthly note, for example "October: 42 drives, 610.3 mi." Null when the month was empty. */
    fun monthlyText(monthName: String, s: PeriodSummary, waitingTotal: Int): String? {
        if (s.drives == 0) return null
        val parts = mutableListOf("$monthName: ${drives(s.drives)}, ${Calc.fmtMiles(s.meters)}.")
        if (s.businessMeters > 0) parts += "Business ${Calc.fmtMiles(s.businessMeters)}."
        if (s.value > 0.005) parts += "Worth ${Calc.fmtMoney(s.value)}."
        waitingLine(waitingTotal)?.let { parts += it }
        return parts.joinToString(" ")
    }

    private fun waitingLine(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 drive needs a category."
        else -> "$n drives need a category."
    }

    /** The quiet note when a rule picked the category. */
    fun autoClassifiedText(meters: Double, categoryName: String): String =
        "Drive saved, ${Calc.fmtMiles(meters)}. Marked ${categoryName.take(40)} by a rule. Tap to change it."
}
