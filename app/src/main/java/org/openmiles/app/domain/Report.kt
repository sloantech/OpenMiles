package org.openmiles.app.domain

import org.openmiles.app.data.Category
import org.openmiles.app.data.Kind
import org.openmiles.app.data.Rate
import org.openmiles.app.data.Source
import org.openmiles.app.data.TripSummary
import java.util.Locale

/** Everything a printed mileage log shows, worked out without any Android code. */
data class ReportData(
    val title: String,
    val subtitle: List<String>,
    val summary: List<Pair<String, String>>,
    val header: List<String>,
    val rows: List<List<String>>
)

object Report {
    val COLUMNS = listOf("Date", "Time", "Miles", "Category", "Purpose / notes", "Value", "Source")

    fun money(cents: Long): String = String.format(Locale.US, "$%,d.%02d", cents / 100, cents % 100)

    fun build(
        range: DateRange,
        trips: List<TripSummary>,
        categories: List<Category>,
        rates: List<Rate>,
        name: String,
        vehicle: String,
        odometerStart: Double?,
        odometerEnd: Double?,
        generatedOn: String
    ): ReportData {
        val byId = categories.associateBy { it.id }
        val inRange = trips.filter { Calc.toDate(it.startTime) in range }.sortedBy { it.startTime }
        fun kindOf(t: TripSummary) = t.categoryId?.let { byId[it] }?.kind

        val totalM = inRange.sumOf { it.distanceMeters }
        val summary = mutableListOf<Pair<String, String>>()
        summary += "Total miles" to fmt1(Calc.miles(totalM)) + " (${inRange.size} drives)"
        Kind.all.forEach { k ->
            val mine = inRange.filter { kindOf(it) == k }
            if (mine.isNotEmpty()) {
                val value = mine.sumOf { Calc.valueOf(it, byId[it.categoryId], rates) }
                summary += "${Kind.label(k)} miles" to fmt1(Calc.miles(mine.sumOf { it.distanceMeters })) +
                    if (k == Kind.PERSONAL) "" else ", value ${Calc.fmtMoney(value)}"
            }
        }
        val unclassified = inRange.count { kindOf(it) == null }
        if (unclassified > 0) summary += "Not classified" to "$unclassified drives"
        val business = inRange.filter { kindOf(it) == Kind.BUSINESS }
        val parking = business.sumOf { it.parkingCents }
        val tolls = business.sumOf { it.tollsCents }
        if (parking + tolls > 0) summary += "Business parking and tolls" to "${money(parking)} parking, ${money(tolls)} tolls"
        odometerTotal(odometerStart, odometerEnd)?.let { total ->
            summary += "Odometer" to "${fmt1(odometerStart!!)} to ${fmt1(odometerEnd!!)} = ${fmt1(total)} miles driven"
            val businessMiles = Calc.miles(business.sumOf { it.distanceMeters })
            if (total > 0) {
                summary += "Business use" to String.format(Locale.US, "%.1f%% of miles driven", 100.0 * businessMiles / total)
            }
        }

        val rows = inRange.map { t ->
            val cat = t.categoryId?.let { byId[it] }
            val notes = buildList {
                if (t.notes.isNotBlank()) add(t.notes.trim())
                if (t.parkingCents > 0) add("parking ${money(t.parkingCents)}")
                if (t.tollsCents > 0) add("tolls ${money(t.tollsCents)}")
            }.joinToString("; ")
            listOf(
                Calc.toDate(t.startTime).toString(),
                "${Calc.fmtTime(t.startTime)}-${Calc.fmtTime(t.endTime)}",
                fmt1(Calc.miles(t.distanceMeters)),
                cat?.name ?: "Not classified",
                notes,
                if (cat == null) "" else Calc.fmtMoney(Calc.valueOf(t, cat, rates)),
                Source.exportLabel(t.source, t.edited)
            )
        }

        val subtitle = buildList {
            add("${Calc.fmtDay(range.from)} to ${Calc.fmtDay(range.to)}")
            if (name.isNotBlank()) add("Driver: ${name.trim()}")
            if (vehicle.isNotBlank()) add("Vehicle: ${vehicle.trim()}")
            add("Made with OpenMiles on $generatedOn")
        }
        return ReportData("Mileage log", subtitle, summary, COLUMNS, rows)
    }

    /** Miles driven in the year, or null unless both readings are there and make sense. */
    fun odometerTotal(start: Double?, end: Double?): Double? =
        if (start != null && end != null && end >= start) end - start else null

    private fun fmt1(v: Double) = String.format(Locale.US, "%,.1f", v)
}
