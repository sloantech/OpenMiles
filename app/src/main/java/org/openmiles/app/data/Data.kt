package org.openmiles.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val route: String = ""
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

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeAll(): Flow<List<Trip>>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Delete
    suspend fun delete(trip: Trip)

    @Query("UPDATE trips SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?)

    @Query("UPDATE trips SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String)

    @Query("UPDATE trips SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind, name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

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

    @Insert
    suspend fun insert(rate: Rate): Long

    @Insert
    suspend fun insertAll(rates: List<Rate>)

    @Delete
    suspend fun delete(rate: Rate)
}

@Database(
    entities = [Trip::class, Category::class, Rate::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun categoryDao(): CategoryDao
    abstract fun rateDao(): RateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openmiles.db"
                ).build().also { instance = it }
            }
    }
}

/** Fills a brand new database with the standard categories and IRS rates. */
object Seed {
    suspend fun run(db: AppDatabase) {
        if (db.categoryDao().count() == 0) {
            Kind.all.forEach { kind ->
                db.categoryDao().insert(
                    Category(name = Kind.label(kind), kind = kind, builtIn = true)
                )
            }
        }
        if (db.rateDao().count() == 0) {
            // IRS standard mileage rates, in dollars per mile.
            // Source: irs.gov/tax-professionals/standard-mileage-rates
            db.rateDao().insertAll(
                listOf(
                    Rate(kind = Kind.BUSINESS, effectiveFrom = "2025-01-01", dollarsPerMile = 0.70),
                    Rate(kind = Kind.MEDICAL, effectiveFrom = "2025-01-01", dollarsPerMile = 0.21),
                    Rate(kind = Kind.CHARITY, effectiveFrom = "2025-01-01", dollarsPerMile = 0.14),
                    Rate(kind = Kind.BUSINESS, effectiveFrom = "2026-01-01", dollarsPerMile = 0.725),
                    Rate(kind = Kind.MEDICAL, effectiveFrom = "2026-01-01", dollarsPerMile = 0.205),
                    Rate(kind = Kind.CHARITY, effectiveFrom = "2026-01-01", dollarsPerMile = 0.14),
                    Rate(kind = Kind.BUSINESS, effectiveFrom = "2026-07-01", dollarsPerMile = 0.76),
                    Rate(kind = Kind.MEDICAL, effectiveFrom = "2026-07-01", dollarsPerMile = 0.235)
                )
            )
        }
    }
}
