package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.BackupCategory
import org.openmiles.app.domain.BackupCodec
import org.openmiles.app.domain.BackupMerge
import org.openmiles.app.domain.BackupRate
import org.openmiles.app.domain.BackupReminder
import org.openmiles.app.domain.BackupTrip
import java.io.StringReader
import java.io.StringWriter

class BackupTest {
    private val cats = listOf(
        BackupCategory(1, "Business", "BUSINESS", true),
        BackupCategory(7, "Client \"A\" visits", "BUSINESS", false)
    )
    private val rates = listOf(BackupRate("BUSINESS", "2026-07-01", 0.76))
    private val trips = listOf(
        BackupTrip(1_700_000_000_000, 1_700_000_600_000, 16093.4, 7, "Line one\nline two, with \"quotes\"", "40.1,-75.2,1;40.2,-75.3,2", "gps"),
        BackupTrip(1_700_100_000_000, 1_700_100_600_000, 500.0, null, "", "", "manual"),
        BackupTrip(1_700_200_000_000, 1_700_200_600_000, 800.0, null, "", "", "gps", edited = true)
    )

    private fun backupText(t: List<BackupTrip> = trips): String {
        val out = StringWriter()
        val n = BackupCodec.write(out, "0.6.0", 1_800_000_000_000, cats, rates) { emit -> t.forEach(emit) }
        assertEquals(t.size, n)
        return out.toString()
    }

    @Test
    fun roundTripKeepsEverything() {
        val got = mutableListOf<BackupTrip>()
        val info = BackupCodec.read(StringReader(backupText())) { got += it }
        assertEquals(trips, got)
        assertEquals(cats, info.categories)
        assertEquals(rates, info.rates)
        assertEquals("0.6.0", info.appVersion)
        assertEquals(1_800_000_000_000, info.createdAt)
        assertEquals(3, info.trips)
        assertEquals(0, info.rejected)
    }

    @Test
    fun damagedEntriesAreLeftOutNotTrusted() {
        val bad = backupText().replace(
            "\"trips\":[",
            "\"trips\":[{\"startTime\":5,\"endTime\":1,\"distanceMeters\":10,\"notes\":\"\",\"route\":\"\",\"source\":\"gps\"}," +
                "{\"startTime\":5,\"endTime\":9,\"distanceMeters\":-1,\"notes\":\"\",\"route\":\"\",\"source\":\"gps\"}," +
                "{\"startTime\":5,\"endTime\":9,\"distanceMeters\":10,\"notes\":\"\",\"route\":\"\",\"source\":\"hacked\"}," +
                "\"not an object\","
        ).replace(
            "\"rates\":[",
            "\"rates\":[{\"kind\":\"BUSINESS\",\"effectiveFrom\":\"2026-02-30\",\"dollarsPerMile\":0.7}," +
                "{\"kind\":\"BUSINESS\",\"effectiveFrom\":\"2026-01-01\",\"dollarsPerMile\":76}," +
                "{\"kind\":\"PERSONAL\",\"effectiveFrom\":\"2026-01-01\",\"dollarsPerMile\":0.5},"
        ).replace(
            "\"categories\":[",
            "\"categories\":[{\"id\":9,\"name\":\"   \",\"kind\":\"BUSINESS\"},{\"id\":10,\"name\":\"X\",\"kind\":\"OTHER\"},"
        )
        val got = mutableListOf<BackupTrip>()
        val info = BackupCodec.read(StringReader(bad)) { got += it }
        assertEquals(trips, got)
        assertEquals(cats, info.categories)
        assertEquals(rates, info.rates)
        assertEquals(4 + 3 + 2, info.rejected)
    }

    @Test
    fun otherFilesAreRefused() {
        assertThrows(BackupCodec.NotABackup::class.java) { BackupCodec.read(StringReader("[]")) {} }
        assertThrows(BackupCodec.NotABackup::class.java) {
            BackupCodec.read(StringReader("{\"semanticSegments\":[]}")) {}
        }
        // A wrong format must be refused before any drive is handed over.
        var handed = 0
        assertThrows(BackupCodec.NotABackup::class.java) {
            BackupCodec.read(StringReader("{\"format\":\"something-else\",\"version\":1,\"trips\":[{\"startTime\":1,\"endTime\":2,\"distanceMeters\":3}]}")) { handed++ }
        }
        assertEquals(0, handed)
        assertThrows(BackupCodec.NotABackup::class.java) {
            BackupCodec.read(StringReader(backupText().replace("\"version\":1", "\"version\":99"))) {}
        }
    }

    @Test
    fun mergeOnlyAddsWhatIsMissing() {
        val existing = setOf(BackupMerge.key(" business ", "BUSINESS"))
        assertEquals(listOf(cats[1]), BackupMerge.missingCategories(cats, existing))
        assertEquals(emptyList<BackupRate>(), BackupMerge.missingRates(rates, setOf("BUSINESS" to "2026-07-01")))
        assertEquals(rates, BackupMerge.missingRates(rates + rates, emptySet()))
    }

    @Test
    fun backupReminderTiming() {
        val day = BackupReminder.DAY_MS
        val now = 2_000_000_000_000L
        assertFalse(BackupReminder.shouldShow(now, 0, 5, 0)) // too few drives to bother
        assertTrue(BackupReminder.shouldShow(now, 0, 25, 0)) // never backed up
        assertFalse(BackupReminder.shouldShow(now, now - 10 * day, 25, 0))
        assertTrue(BackupReminder.shouldShow(now, now - 31 * day, 25, 0))
        assertFalse(BackupReminder.shouldShow(now, 0, 25, now + day)) // snoozed
    }
}
