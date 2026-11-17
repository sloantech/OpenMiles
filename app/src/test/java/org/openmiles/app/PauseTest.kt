package org.openmiles.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openmiles.app.domain.Pause
import java.time.LocalDateTime
import java.time.ZoneId

class PauseTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2026, 11, 17, 15, 30).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun pauseLengths() {
        assertEquals(now + 3_600_000L, Pause.until(Pause.Option.HOUR, now, zone))
        assertEquals(
            LocalDateTime.of(2026, 11, 18, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            Pause.until(Pause.Option.TODAY, now, zone)
        )
        assertEquals(now + 86_400_000L, Pause.until(Pause.Option.DAY, now, zone))
        assertEquals(now + 7 * 86_400_000L, Pause.until(Pause.Option.WEEK, now, zone))
        assertEquals(Pause.FOREVER, Pause.until(Pause.Option.UNTIL_RESUMED, now, zone))
    }

    @Test
    fun pausedOnlyUntilTheEnd() {
        assertFalse(Pause.isPaused(now, 0L))
        assertTrue(Pause.isPaused(now, now + 1))
        assertFalse(Pause.isPaused(now, now))
        assertTrue(Pause.isPaused(now, Pause.FOREVER))
    }
}
