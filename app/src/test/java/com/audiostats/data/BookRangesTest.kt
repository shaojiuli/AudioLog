package com.audiostats.data

import com.audiostats.ui.listenRangeText
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class BookRangesTest {
    private fun range(start: String, end: String, minutes: Long = 10) =
        ListenRange(LocalDateTime.parse(start), LocalDateTime.parse(end), minutes * 60_000)

    @Test fun sameDayAndAdjacentDaysShareOneDisplayRowWithoutCountingGaps() {
        val rows = Books.displayRanges(listOf(
            range("2026-11-10T08:10", "2026-11-10T08:20"),
            range("2026-11-10T20:00", "2026-11-10T20:10"),
            range("2026-11-11T23:50", "2026-11-12T00:00"),
            range("2026-11-13T12:00", "2026-11-13T12:10"),
        ).reversed())
        val row = rows.single()
        assertEquals(LocalDateTime.parse("2026-11-10T08:10"), row.start)
        assertEquals(LocalDateTime.parse("2026-11-13T12:10"), row.end)
        assertEquals(40 * 60_000L, row.ms)
        assertEquals("11月10日 08:10 - 11月13日 12:10", listenRangeText(row))
    }

    @Test fun aMissingCalendarDayStartsAnotherRowAndNewestIsFirst() {
        val old = range("2026-11-10T08:10", "2026-11-10T08:20")
        val recent = range("2026-11-12T20:00", "2026-11-12T20:10")
        assertEquals(listOf(recent, old), Books.displayRanges(listOf(old, recent)))
    }

    @Test fun adjacentDaysAcrossYearsMergeAndBothEndpointsShowYear() {
        val row = Books.displayRanges(listOf(
            range("2026-12-31T23:30", "2026-12-31T23:40"),
            range("2027-01-01T01:20", "2027-01-01T01:30"),
        )).single()
        assertEquals("2026年12月31日 23:30 - 2027年1月1日 01:30", listenRangeText(row))
        assertEquals(20 * 60_000L, row.ms)
    }

    @Test fun singleDayStillShowsDateAtBothEndsAndEmptyInputWorks() {
        assertEquals("11月12日 08:10 - 11月12日 08:20",
            listenRangeText(range("2026-11-12T08:10", "2026-11-12T08:20")))
        assertTrue(Books.displayRanges(emptyList()).isEmpty())
    }
}
