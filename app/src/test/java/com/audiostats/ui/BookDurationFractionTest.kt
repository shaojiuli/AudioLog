package com.audiostats.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDurationFractionTest {
    @Test fun barsCompareCumulativeDurationAgainstTheLongestBook() {
        assertEquals(1f, bookDurationFraction(120_000, 120_000), 0f)
        assertEquals(0.5f, bookDurationFraction(60_000, 120_000), 0f)
        assertEquals(0.25f, bookDurationFraction(30_000, 120_000), 0f)
    }

    @Test fun emptyAndInvalidDurationsNeverProduceAnInvalidBarWidth() {
        assertEquals(0f, bookDurationFraction(0, 0), 0f)
        assertEquals(0f, bookDurationFraction(-1, 100), 0f)
        assertEquals(0f, bookDurationFraction(0, -1), 0f)
        assertEquals(1f, bookDurationFraction(100, 50), 0f)
        assertEquals(1f, bookDurationFraction(100, 0), 0f)
    }

    @Test fun largeTotalsAndVeryShortBooksRetainTheirRelativeDuration() {
        assertEquals(1f, bookDurationFraction(Long.MAX_VALUE, Long.MAX_VALUE), 0f)
        assertEquals(0.5f, bookDurationFraction(Long.MAX_VALUE / 2, Long.MAX_VALUE), 0.000001f)
        assertEquals(0.001f, bookDurationFraction(1_000, 1_000_000), 0.000001f)
    }
}
