package org.openmiles.app.data

import android.content.Context
import org.openmiles.app.domain.AutoClassify
import org.openmiles.app.domain.PlaceRule
import org.openmiles.app.domain.WorkHours
import java.time.ZoneId

/** Everything needed to auto-classify a drive, loaded at once. */
data class RuleSet(val places: List<PlaceRule>, val work: WorkHours, val categoryIds: Set<Long>) {
    fun categoryFor(start: Pair<Double, Double>?, end: Pair<Double, Double>?, startMs: Long): Long? =
        AutoClassify.categoryFor(start, end, startMs, places, work, categoryIds, ZoneId.systemDefault())

    fun categoryForRoute(route: String, startMs: Long): Long? {
        val ends = AutoClassify.ends(route)
        return categoryFor(ends?.first, ends?.second, startMs)
    }

    fun ignoresRoute(route: String): Boolean {
        val ends = AutoClassify.ends(route) ?: return false
        return AutoClassify.ignored(ends.first, ends.second, places)
    }
}

/** The work-hours rule is a single setting, kept with the app's other settings. */
object WorkHoursStore {
    private const val PREFS = "rules"

    fun load(context: Context): WorkHours {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = WorkHours()
        return WorkHours(
            enabled = p.getBoolean("work_on", false),
            days = if (p.contains("work_days")) WorkHours.parseDays(p.getString("work_days", "")) else default.days,
            startMin = p.getInt("work_start", default.startMin).coerceIn(0, 1439),
            endMin = p.getInt("work_end", default.endMin).coerceIn(0, 1439),
            categoryId = p.getLong("work_category", -1L).takeIf { it > 0 }
        )
    }

    fun save(context: Context, work: WorkHours) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("work_on", work.enabled)
            .putString("work_days", work.daysText())
            .putInt("work_start", work.startMin)
            .putInt("work_end", work.endMin)
            .putLong("work_category", work.categoryId ?: -1L)
            .apply()
    }

    fun isSet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("work_on")
}

object Rules {
    private fun Place.toRule() = PlaceRule(id, name, lat, lng, radiusMeters, categoryId, ignoreDrives)

    suspend fun load(context: Context, db: AppDatabase): RuleSet = RuleSet(
        db.placeDao().all().map { it.toRule() },
        WorkHoursStore.load(context),
        db.categoryDao().all().map { it.id }.toSet()
    )

    /** For background threads that cannot suspend, such as inside a database transaction. */
    fun loadNow(context: Context, db: AppDatabase): RuleSet = RuleSet(
        db.placeDao().allNow().map { it.toRule() },
        WorkHoursStore.load(context),
        db.categoryDao().allNow().map { it.id }.toSet()
    )
}
