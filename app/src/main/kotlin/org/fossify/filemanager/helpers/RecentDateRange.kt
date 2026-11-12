package org.fossify.filemanager.helpers

import java.time.LocalDate
import java.time.ZoneId

/** A local-calendar date interval represented as millisecond timestamps. */
data class RecentDateRange(val startInclusiveMillis: Long, val endExclusiveMillis: Long) {
    init {
        require(startInclusiveMillis < endExclusiveMillis) { "The start date must not be after the end date." }
    }

    fun contains(timestampMillis: Long): Boolean =
        timestampMillis >= startInclusiveMillis && timestampMillis < endExclusiveMillis

    companion object {
        fun from(startDate: LocalDate, endDate: LocalDate, zone: ZoneId = ZoneId.systemDefault()): RecentDateRange {
            require(!startDate.isAfter(endDate)) { "The start date must not be after the end date." }
            return RecentDateRange(
                startInclusiveMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                endExclusiveMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
    }
}
