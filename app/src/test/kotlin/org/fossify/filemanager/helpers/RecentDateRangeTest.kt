package org.fossify.filemanager.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecentDateRangeTest {
    @Test
    fun equalDatesIncludeTheWholeCalendarDay() {
        val range = RecentDateRange.from(LocalDate.parse("2025-03-15"), LocalDate.parse("2025-03-15"), UTC)
        assertTrue(range.contains(Instant.parse("2025-03-15T00:00:00Z").toEpochMilli()))
        assertTrue(range.contains(Instant.parse("2025-03-15T23:59:59.999Z").toEpochMilli()))
        assertFalse(range.contains(Instant.parse("2025-03-16T00:00:00Z").toEpochMilli()))
        assertFalse(range.contains(Instant.parse("2025-03-14T23:59:59.999Z").toEpochMilli()))
    }

    @Test
    fun intervalUsesDeviceZoneLocalMidnightsAndMillisecondTimestamps() {
        val range = RecentDateRange.from(LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-15"), NEW_YORK)
        assertEquals(Instant.parse("2025-03-01T05:00:00Z").toEpochMilli(), range.startInclusiveMillis)
        assertEquals(Instant.parse("2025-03-16T04:00:00Z").toEpochMilli(), range.endExclusiveMillis)
        assertTrue(range.contains(range.startInclusiveMillis))
        assertFalse(range.contains(range.endExclusiveMillis))
    }

    @Test
    fun leapDayIsHandledAsARealCalendarDate() {
        val range = RecentDateRange.from(LocalDate.parse("2024-02-29"), LocalDate.parse("2024-02-29"), UTC)
        assertTrue(range.contains(Instant.parse("2024-02-29T12:00:00Z").toEpochMilli()))
        assertFalse(range.contains(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()))
    }

    @Test
    fun invalidDateRangeIsRejected() {
        val error = runCatching {
            RecentDateRange.from(LocalDate.parse("2025-03-16"), LocalDate.parse("2025-03-15"), UTC)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun daylightSavingTransitionDoesNotAssumeTwentyFourHoursPerDay() {
        val range = RecentDateRange.from(LocalDate.parse("2025-03-09"), LocalDate.parse("2025-03-09"), NEW_YORK)
        assertEquals(23 * 60 * 60 * 1000L, range.endExclusiveMillis - range.startInclusiveMillis)
        assertTrue(range.contains(Instant.parse("2025-03-10T03:59:59.999Z").toEpochMilli()))
        assertFalse(range.contains(Instant.parse("2025-03-10T04:00:00Z").toEpochMilli()))
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
