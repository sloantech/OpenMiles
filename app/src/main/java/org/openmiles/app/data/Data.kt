package org.openmiles.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The four tax purposes. Every category belongs to exactly one of these. */
object Kind {
    const val BUSINESS = "BUSINESS"
    const val PERSONAL = "PERSONAL"
    const val MEDICAL = "MEDICAL"
    const val CHARITY = "CHARITY"

    val all = listOf(BUSINESS, PERSONAL, MEDICAL, CHARITY)

    fun label(kind: String): String = when (kind) {
        BUSINESS -> "Business"
        PERSONAL -> "Personal"
        MEDICAL -> "Medical"
        CHARITY -> "Charity"
        else -> kind
    }
}

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val builtIn: Boolean = false
)

/** Where a drive came from. Shown in the app and in exports, which matters for tax records. */
object Source {
    const val GPS = "gps"
    const val MANUAL = "manual"
    const val TIMELINE = "timeline"
    const val MILEIQ = "mileiq"

    fun label(source: String): String = when (source) {
        MANUAL -> "Added by hand"
        TIMELINE -> "Google Timeline"
        MILEIQ -> "MileIQ import"
        else -> "GPS"
    }

    /** The label used in exports, which also says when a drive was changed by hand. */
    fun exportLabel(source: String, edited: Boolean): String =
        if (edited && source != MANUAL) "${label(source)}, edited" else label(source)
}

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Double,
    /** Null means "not classified yet". */
    val categoryId: Long? = null,
    val notes: String = "",
    /** Route as "lat,lng,time;lat,lng,time;..." */
    val route: String = "",
    /** One of the [Source] values. Added in database version 2. */
    @ColumnInfo(defaultValue = Source.GPS) val source: String = Source.GPS,
    /** True once the date, times or miles were changed by hand, or the drive was split or merged. */
    @ColumnInfo(defaultValue = "0") val edited: Boolean = false,
    /** Parking and tolls paid for this drive, in cents. Deductible on top of mileage for business. */
    @ColumnInfo(defaultValue = "0") val parkingCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val tollsCents: Long = 0
)

/** Just the times of a drive, for checking overlaps. */
data class TimeSpan(val startTime: Long, val endTime: Long)

/**
 * What the screens need to know about a drive. It leaves out the route, which can be large,
 * so the drive list stays fast and light on memory after years of use.
 */
data class TripSummary(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val distanceMeters: Double,
    val categoryId: Long?,
    val notes: String,
    val source: String = Source.GPS,
    val edited: Boolean = false,
    val parkingCents: Long = 0,
    val tollsCents: Long = 0
)

/** A mileage rate that starts on a date. The newest one on or before the trip date wins. */
@Entity(tableName = "rates")
data class Rate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    /** ISO date, for example 2026-07-01 */
    val effectiveFrom: String,
    val dollarsPerMile: Double
)

/** A place the person named. Used for auto-classify rules and for places to ignore. */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double = 150.0,
    /** Drives that start or end here get this category. Null: no rule. */
    val categoryId: Long? = null,
    /** Drives that start and end here are not recorded. */
    @ColumnInfo(defaultValue = "0") val ignoreDrives: Boolean = false
)

@Dao
interface TripDao {
    @Query(
        "SELECT id, startTime, endTime, distanceMeters, categoryId, notes, source, edited, parkingCents, tollsCents " +
            "FROM trips ORDER BY startTime DESC"
    )
    fun observeSummaries(): Flow<List<TripSummary>>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Insert
    suspend fun insertAll(trips: List<Trip>)

    @Query("SELECT startTime, endTime FROM trips")
    suspend fun allSpans(): List<TimeSpan>

    /** Full drives a page at a time, oldest first, so a backup never loads every route at once. */
    @Query("SELECT * FROM trips ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<Trip>

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM trips WHERE categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories)")
    suspend fun countUnclassified(): Int

    /** Drives that started in [fromMs, toMs), without the route, for the summaries. */
    @Query(
        "SELECT id, startTime, endTime, distanceMeters, categoryId, notes, source, edited, parkingCents, tollsCents " +
            "FROM trips WHERE startTime >= :fromMs AND startTime < :toMs"
    )
    suspend fun summariesBetween(fromMs: Long, toMs: Long): List<TripSummary>

    // Plain (non-suspend) versions for backup and restore, which run on a background thread
    // inside one database transaction. Never call these from the main thread.
    @Query("SELECT * FROM trips ORDER BY id LIMIT :limit OFFSET :offset")
    fun pageNow(limit: Int, offset: Int): List<Trip>

    @Query("SELECT startTime, endTime FROM trips")
    fun allSpansNow(): List<TimeSpan>

    @Insert
    fun insertAllNow(trips: List<Trip>)

    /** Unclassified drives after a given id, for applying rules in batches. */
    @Query("SELECT * FROM trips WHERE categoryId IS NULL AND id > :afterId ORDER BY id LIMIT :limit")
    fun unclassifiedAfterNow(afterId: Long, limit: Int): List<Trip>

    @Query("UPDATE trips SET categoryId = :categoryId WHERE id = :id AND categoryId IS NULL")
    fun setCategoryIfEmptyNow(id: Long, categoryId: Long)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): Trip?

    @Update
    suspend fun update(trip: Trip)

    @Query("SELECT COUNT(*) FROM trips WHERE startTime = :start")
    suspend fun countStartingAt(start: Long): Int

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE trips SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?)

    @Query("UPDATE trips SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String)

    @Query("UPDATE trips SET parkingCents = :parking, tollsCents = :tolls WHERE id = :id")
    suspend fun setExpenses(id: Long, parking: Long, tolls: Long)

    @Query("UPDATE trips SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind, name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories")
    suspend fun all(): List<Category>

    @Query("SELECT * FROM categories")
    fun allNow(): List<Category>

    @Insert
    fun insertNow(category: Category): Long

    @Query("SELECT * FROM categories WHERE builtIn = 1 AND kind = :kind LIMIT 1")
    suspend fun builtIn(kind: String): Category?

    @Insert
    suspend fun insert(category: Category): Long

    @Delete
    suspend fun delete(category: Category)
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates ORDER BY kind, effectiveFrom DESC")
    fun observeAll(): Flow<List<Rate>>

    @Query("SELECT COUNT(*) FROM rates")
    suspend fun count(): Int

    @Query("SELECT * FROM rates")
    suspend fun all(): List<Rate>

    @Query("SELECT * FROM rates")
    fun allNow(): List<Rate>

    @Insert
    fun insertAllNow(rates: List<Rate>)

    @Insert
    suspend fun insert(rate: Rate): Long

    @Insert
    suspend fun insertAll(rates: List<Rate>)

    @Delete
    suspend fun delete(rate: Rate)

    /** Used so two rates never start on the same date for the same kind. */
    @Query("DELETE FROM rates WHERE kind = :kind AND effectiveFrom = :effectiveFrom")
    suspend fun deleteFor(kind: String, effectiveFrom: String)
}

/** Odometer readings at the start and end of a year. IRS records ask for total miles driven. */
@Entity(tableName = "odometer")
data class Odometer(
    @PrimaryKey val year: Int,
    val startMiles: Double?,
    val endMiles: Double?
)

@Dao
interface OdometerDao {
    @Query("SELECT * FROM odometer ORDER BY year DESC")
    fun observeAll(): Flow<List<Odometer>>

    @Query("SELECT * FROM odometer")
    suspend fun all(): List<Odometer>

    @Query("SELECT * FROM odometer")
    fun allNow(): List<Odometer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(reading: Odometer)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissingNow(reading: Odometer): Long
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places")
    suspend fun all(): List<Place>

    @Query("SELECT * FROM places")
    fun allNow(): List<Place>

    @Insert
    suspend fun insert(place: Place): Long

    @Insert
    fun insertNow(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Delete
    suspend fun delete(place: Place)

    @Query("UPDATE places SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}

@Database(
    entities = [Trip::class, Category::class, Rate::class, Place::class, Odometer::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun categoryDao(): CategoryDao
    abstract fun rateDao(): RateDao
    abstract fun placeDao(): PlaceDao
    abstract fun odometerDao(): OdometerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openmiles.db"
                )
                    // Never wipe the database on an upgrade. A missing migration must crash in
                    // testing, not silently delete someone's drives.
                    .addMigrations(*ALL_MIGRATIONS)
                    .build().also { instance = it }
            }

        /** Version 1 to 2: remember where each drive came from. Old drives were all GPS. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'gps'")
            }
        }

        /** Version 2 to 3: mark drives changed by hand. Old drives were not edited. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `edited` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 3 to 4: named places, for auto-classify rules and places to ignore. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                        "`radiusMeters` REAL NOT NULL, `categoryId` INTEGER, " +
                        "`ignoreDrives` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** Version 4 to 5: parking and tolls on drives, and yearly odometer readings. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `parkingCents` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `tollsCents` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `odometer` (`year` INTEGER NOT NULL, `startMiles` REAL, " +
                        "`endMiles` REAL, PRIMARY KEY(`year`))"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}

/** Fills a brand new database with the standard categories and IRS rates. */
object Seed {
    /**
     * IRS standard mileage rates, in dollars per mile.
     * Source: https://www.irs.gov/tax-professionals/standard-mileage-rates
     * Charity is set by law at 14 cents and has not changed.
     */
    val defaultRates: List<Rate> = listOf(
        Rate(kind = Kind.BUSINESS, effectiveFrom = "2025-01-01", dollarsPerMile = 0.70),
        Rate(kind = Kind.MEDICAL, effectiveFrom = "2025-01-01", dollarsPerMile = 0.21),
        Rate(kind = Kind.CHARITY, effectiveFrom = "2025-01-01", dollarsPerMile = 0.14),
        Rate(kind = Kind.BUSINESS, effectiveFrom = "2026-01-01", dollarsPerMile = 0.725),
        Rate(kind = Kind.MEDICAL, effectiveFrom = "2026-01-01", dollarsPerMile = 0.205),
        Rate(kind = Kind.CHARITY, effectiveFrom = "2026-01-01", dollarsPerMile = 0.14),
        Rate(kind = Kind.BUSINESS, effectiveFrom = "2026-07-01", dollarsPerMile = 0.76),
        Rate(kind = Kind.MEDICAL, effectiveFrom = "2026-07-01", dollarsPerMile = 0.235)
    )

    suspend fun run(db: AppDatabase) {
        if (db.categoryDao().count() == 0) {
            Kind.all.forEach { kind ->
                db.categoryDao().insert(
                    Category(name = Kind.label(kind), kind = kind, builtIn = true)
                )
            }
        }
        if (db.rateDao().count() == 0) {
            db.rateDao().insertAll(defaultRates)
        }
    }
}
