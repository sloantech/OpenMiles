package org.openmiles.app.domain

import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.TripSummary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A span of days, including both the first and the last day. */
data class DateRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "The end date must not be before the start date." }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)

    companion object {
        fun month(day: LocalDate): DateRange {
            val ym = YearMonth.from(day)
            return DateRange(ym.atDay(1), ym.atEndOfMonth())
        }

        fun lastMonth(today: LocalDate): DateRange = month(today.withDayOfMonth(1).minusDays(1))

        fun quarter(day: LocalDate): DateRange {
            val firstMonth = ((day.monthValue - 1) / 3) * 3 + 1
            val start = LocalDate.of(day.year, firstMonth, 1)
            return DateRange(start, YearMonth.from(start.plusMonths(2)).atEndOfMonth())
        }

        fun lastQuarter(today: LocalDate): DateRange = quarter(quarter(today).from.minusDays(1))

        fun year(year: Int): DateRange = DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
    }
}

object Calc {
    const val METERS_PER_MILE = 1609.344

    fun miles(meters: Double): Double = meters / METERS_PER_MILE

    fun toDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * The rate in dollars per mile for a kind of drive on a given date.
     * Personal drives are always zero. If no rate exists yet, the answer is zero.
     */
    fun rateFor(kind: String, date: LocalDate, rates: List<Rate>): Double {
        if (kind == Kind.PERSONAL) return 0.0
        return rates
            .filter { it.kind == kind }
            .mapNotNull { rate ->
                val start = runCatching { LocalDate.parse(rate.effectiveFrom) }.getOrNull()
                if (start != null && !start.isAfter(date)) start to rate.dollarsPerMile else null
            }
            .maxByOrNull { it.first }
            ?.second ?: 0.0
    }

    /** Dollar value of one trip. Unclassified trips are worth nothing yet. */
    fun valueOf(trip: TripSummary, category: Category?, rates: List<Rate>): Double {
        if (category == null) return 0.0
        return miles(trip.distanceMeters) * rateFor(category.kind, toDate(trip.startTime), rates)
    }

    fun fmtMiles(meters: Double): String =
        String.format(Locale.US, "%.1f mi", miles(meters))

    fun fmtMoney(dollars: Double): String =
        String.format(Locale.US, "\$%.2f", dollars)

    private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun fmtDate(millis: Long): String = toDate(millis).format(dateFmt)

    private val dayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun fmtDay(day: LocalDate): String = day.format(dayFmt)

    fun fmtTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFmt)
}

object Csv {
    /**
     * Quotes a text field for CSV. Fields that start with =, +, -, or @ are prefixed with an
     * apostrophe so a spreadsheet shows them as text instead of running them as a formula.
     */
    private fun esc(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    /** A spreadsheet-ready log of every trip in the given year. */
    fun build(
        year: Int,
        trips: List<TripSummary>,
        categories: List<Category>,
        rates: List<Rate>
    ): String = buildRange(DateRange.year(year), trips, categories, rates)

    /** A spreadsheet-ready log of every trip from the first day through the last day of the range. */
    fun buildRange(
        range: DateRange,
        trips: List<TripSummary>,
        categories: List<Category>,
        rates: List<Rate>
    ): String {
        val byId = categories.associateBy { it.id }
        val sb = StringBuilder()
        sb.append("Date,Start,End,Miles,Category,Type,Rate per mile,Value,Notes\n")
        trips
            .filter { Calc.toDate(it.startTime) in range }
            .sortedBy { it.startTime }
            .forEach { t ->
                val cat = t.categoryId?.let { byId[it] }
                val rate = cat?.let { Calc.rateFor(it.kind, Calc.toDate(t.startTime), rates) } ?: 0.0
                val value = Calc.valueOf(t, cat, rates)
                sb.append(
                    listOf(
                        Calc.toDate(t.startTime).toString(),
                        Calc.fmtTime(t.startTime),
                        Calc.fmtTime(t.endTime),
                        String.format(Locale.US, "%.2f", Calc.miles(t.distanceMeters)),
                        esc(cat?.name ?: "Unclassified"),
                        esc(cat?.let { Kind.label(it.kind) } ?: ""),
                        String.format(Locale.US, "%.3f", rate),
                        String.format(Locale.US, "%.2f", value),
                        esc(t.notes)
                    ).joinToString(",")
                ).append("\n")
            }
        return sb.toString()
    }
}
