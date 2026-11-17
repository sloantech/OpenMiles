package org.openmiles.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.openmiles.app.data.AppDatabase
import org.openmiles.app.data.Source
import org.openmiles.app.data.Trip
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds a database exactly as version 0.3.0 made it, with a drive in it, then opens it with
 * the current app. The drive must survive, and Room must accept the upgraded tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseUpgradeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun version1DatabaseUpgradesWithoutLosingDrives() {
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                    "`categoryId` INTEGER, `notes` TEXT NOT NULL, `route` TEXT NOT NULL)"
            )
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `kind` TEXT NOT NULL, `builtIn` INTEGER NOT NULL)"
            )
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `rates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `effectiveFrom` TEXT NOT NULL, `dollarsPerMile` REAL NOT NULL)"
            )
            old.execSQL(
                "INSERT INTO trips (startTime, endTime, distanceMeters, categoryId, notes, route) " +
                    "VALUES (1700000000000, 1700000600000, 16093.44, NULL, 'kept', '')"
            )
            old.version = 1
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val trips = runBlocking { db.tripDao().observeSummaries().first() }
            assertEquals(1, trips.size)
            assertEquals("kept", trips[0].notes)
            assertEquals(Source.GPS, trips[0].source)
            assertEquals(16093.44, trips[0].distanceMeters, 1e-6)

            runBlocking {
                db.tripDao().insert(Trip(startTime = 1, endTime = 2, distanceMeters = 5.0, source = Source.MANUAL))
            }
            val sources = runBlocking { db.tripDao().observeSummaries().first() }.map { it.source }.toSet()
            assertEquals(setOf(Source.GPS, Source.MANUAL), sources)
        } finally {
            db.close()
        }
    }

    @Test
    fun freshInstallCreatesTheCurrentDatabase() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            runBlocking { db.tripDao().insert(Trip(startTime = 1, endTime = 2, distanceMeters = 5.0)) }
            assertEquals(Source.GPS, runBlocking { db.tripDao().observeSummaries().first() }[0].source)
        } finally {
            db.close()
        }
    }

    @Test
    fun backupPagingAndTransactionInsertWork() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.runInTransaction {
                db.tripDao().insertAllNow((1..450).map { Trip(startTime = it.toLong(), endTime = it + 1L, distanceMeters = 1.0) })
            }
            val seen = mutableListOf<Long>()
            var offset = 0
            while (true) {
                val page = db.tripDao().pageNow(200, offset)
                if (page.isEmpty()) break
                seen += page.map { it.startTime }
                offset += page.size
            }
            assertEquals((1L..450L).toList(), seen)
            // A failure inside the transaction leaves nothing behind.
            runCatching {
                db.runInTransaction {
                    db.tripDao().insertAllNow(listOf(Trip(startTime = 999, endTime = 1000, distanceMeters = 1.0)))
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(450, db.tripDao().allSpansNow().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun version2DatabaseUpgradesWithoutLosingDrives() {
        val name = "upgrade-test-v2.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                    "`categoryId` INTEGER, `notes` TEXT NOT NULL, `route` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL DEFAULT 'gps')"
            )
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `kind` TEXT NOT NULL, `builtIn` INTEGER NOT NULL)"
            )
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `rates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `effectiveFrom` TEXT NOT NULL, `dollarsPerMile` REAL NOT NULL)"
            )
            old.execSQL(
                "INSERT INTO trips (startTime, endTime, distanceMeters, categoryId, notes, route, source) " +
                    "VALUES (1700000000000, 1700000600000, 5000.0, NULL, 'from v2', '', 'timeline')"
            )
            old.version = 2
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val trips = runBlocking { db.tripDao().observeSummaries().first() }
            assertEquals(1, trips.size)
            assertEquals("from v2", trips[0].notes)
            assertEquals(Source.TIMELINE, trips[0].source)
            assertEquals(false, trips[0].edited)
        } finally {
            db.close()
        }
    }
}
