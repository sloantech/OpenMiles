package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openmiles.app.domain.ReminderTime
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTimeTest {
    private val zone = ZoneId.of("America/New_York")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nextSundayEvening() {
        // Tuesday Nov 17, 2026 -> Sunday Nov 22 at 6 PM
        assertEquals(ms(2026, 11, 22, 18), ReminderTime.next(ms(2026, 11, 17, 9), zone))
        // Sunday before 6 PM -> same day
        assertEquals(ms(2026, 11, 22, 18), ReminderTime.next(ms(2026, 11, 22, 17, 59), zone))
        // Sunday at or after 6 PM -> next week
        assertEquals(ms(2026, 11, 29, 18), ReminderTime.next(ms(2026, 11, 22, 18), zone))
    }

    @Test
    fun text() {
        assertNull(ReminderTime.text(0))
        assertEquals("1 drive is waiting for a category.", ReminderTime.text(1))
        assertEquals("12 drives are waiting for a category.", ReminderTime.text(12))
    }
}
