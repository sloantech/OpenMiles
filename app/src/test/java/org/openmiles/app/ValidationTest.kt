package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.UpdateReminder
import org.openmiles.app.domain.Validation
import java.time.LocalDate

class ValidationTest {
    @Test
    fun ratesMustBeRealNumbersInRange() {
        assertTrue(Validation.isValidRate(0.0))
        assertTrue(Validation.isValidRate(0.76))
        assertTrue(Validation.isValidRate(10.0))
        assertFalse(Validation.isValidRate(-0.01))
        assertFalse(Validation.isValidRate(10.01))
        assertFalse(Validation.isValidRate(76.0))
        assertFalse(Validation.isValidRate(Double.NaN))
        assertFalse(Validation.isValidRate(Double.POSITIVE_INFINITY))
        assertFalse(Validation.isValidRate(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun datesMustBeRealCalendarDays() {
        assertEquals(LocalDate.of(2026, 7, 1), Validation.parseDay("2026-07-01"))
        assertEquals(LocalDate.of(2026, 7, 1), Validation.parseDay("  2026-07-01 "))
        assertEquals(LocalDate.of(2028, 2, 29), Validation.parseDay("2028-02-29"))
        assertNull(Validation.parseDay("2026-02-30"))
        assertNull(Validation.parseDay("2027-02-29"))
        assertNull(Validation.parseDay("07/01/2026"))
        assertNull(Validation.parseDay(""))
        assertNull(Validation.parseDay("'; DROP TABLE rates;--"))
    }

    @Test
    fun onlyPlainHttpsLinksAreOpened() {
        assertTrue(Validation.isSafeWebLink("https://paypal.me/joshuasloan"))
        assertTrue(Validation.isSafeWebLink("HTTPS://github.com/sloantech/OpenMiles"))
        assertFalse(Validation.isSafeWebLink(""))
        assertFalse(Validation.isSafeWebLink("http://example.com"))
        assertFalse(Validation.isSafeWebLink("javascript:alert(1)"))
        assertFalse(Validation.isSafeWebLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(Validation.isSafeWebLink("file:///data/data/org.openmiles.app/databases/x"))
        assertFalse(Validation.isSafeWebLink("content://org.openmiles.app.fileprovider/exports/a.csv"))
        assertFalse(Validation.isSafeWebLink("https://"))
        assertFalse(Validation.isSafeWebLink("https:///path"))
        assertFalse(Validation.isSafeWebLink("https://paypal.me@evil.example/"))
        assertFalse(Validation.isSafeWebLink("https://bad host/"))
    }

    @Test
    fun manualMilesMustBeSensible() {
        assertTrue(Validation.isValidManualMiles(0.1))
        assertTrue(Validation.isValidManualMiles(1000.0))
        assertFalse(Validation.isValidManualMiles(0.0))
        assertFalse(Validation.isValidManualMiles(-3.0))
        assertFalse(Validation.isValidManualMiles(1000.5))
        assertFalse(Validation.isValidManualMiles(null))
        assertFalse(Validation.isValidManualMiles(Double.NaN))
    }

    @Test
    fun updateReminderIsGentle() {
        val day = UpdateReminder.DAY_MS
        val built = 1_000_000_000_000L
        assertFalse(UpdateReminder.shouldShow(built + 59 * day, built, 0))
        assertTrue(UpdateReminder.shouldShow(built + 60 * day, built, 0))
        // Snoozed: quiet until the snooze ends.
        assertFalse(UpdateReminder.shouldShow(built + 70 * day, built, built + 90 * day))
        assertTrue(UpdateReminder.shouldShow(built + 90 * day, built, built + 90 * day))
        // A missing build time never shows it.
        assertFalse(UpdateReminder.shouldShow(built, 0, 0))
        assertEquals(2, UpdateReminder.monthsOld(built + 61 * day, built))
    }
}
