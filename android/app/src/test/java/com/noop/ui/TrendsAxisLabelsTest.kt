package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendsAxisLabelsTest {
    private fun day(value: String) = java.time.LocalDate.parse(value).toEpochDay() * 86_400L

    @Test fun fewerThanTwoDatesHasNoAxisLabels() {
        assertEquals(emptyList<TrendAxisLabel>(), trendAxisLabels(emptyList()))
        assertEquals(emptyList<TrendAxisLabel>(), trendAxisLabels(listOf("2026-07-16")))
    }

    @Test fun twoDatesUseThePlotEndpointsWithoutDuplicatingTheFirstDate() {
        assertEquals(
            listOf(
                TrendAxisLabel("2026-07-15", TrendAxisAnchor.START),
                TrendAxisLabel("2026-07-16", TrendAxisAnchor.END),
            ),
            trendAxisLabels(listOf("2026-07-15", "2026-07-16")),
        )
    }

    @Test fun longerRangesUseStartCenterAndEndAnchors() {
        assertEquals(
            listOf(
                TrendAxisLabel("2026-07-12", TrendAxisAnchor.START),
                TrendAxisLabel("2026-07-14", TrendAxisAnchor.CENTER),
                TrendAxisLabel("2026-07-16", TrendAxisAnchor.END),
            ),
            trendAxisLabels(
                listOf(
                    "2026-07-12",
                    "2026-07-13",
                    "2026-07-14",
                    "2026-07-15",
                    "2026-07-16",
                ),
            ),
        )
    }

    @Test fun sparseWindowLabelsUseTheSelectedCalendarBoundaries() {
        assertEquals(
            listOf(
                TrendAxisLabel("2026-09-01", TrendAxisAnchor.START),
                TrendAxisLabel("2026-09-06", TrendAxisAnchor.CENTER),
                TrendAxisLabel("2026-09-11", TrendAxisAnchor.END),
            ),
            trendAxisLabels(listOf("2026-09-03", "2026-09-05"), day("2026-09-01")..day("2026-09-11")),
        )
    }

    @Test fun legacySparseDatesUseTheCalendarMidpointRatherThanTheMiddleReading() {
        assertEquals(
            listOf(
                TrendAxisLabel("2026-09-01", TrendAxisAnchor.START),
                TrendAxisLabel("2026-09-06", TrendAxisAnchor.CENTER),
                TrendAxisLabel("2026-09-11", TrendAxisAnchor.END),
            ),
            trendAxisLabels(listOf("2026-09-01", "2026-09-02", "2026-09-11")),
        )
    }

    @Test fun oneReadingKeepsTheWindowAndOneDayWindowsHaveOneCenteredLabel() {
        assertEquals(
            listOf(
                TrendAxisLabel("2026-09-01", TrendAxisAnchor.START),
                TrendAxisLabel("2026-09-06", TrendAxisAnchor.CENTER),
                TrendAxisLabel("2026-09-11", TrendAxisAnchor.END),
            ),
            trendAxisLabels(listOf("2026-09-05"), day("2026-09-01")..day("2026-09-11")),
        )
        assertEquals(
            listOf(TrendAxisLabel("2026-09-05", TrendAxisAnchor.CENTER)),
            trendAxisLabels(listOf("2026-09-05"), day("2026-09-05")..day("2026-09-05")),
        )
    }

    @Test fun invalidDomainKeepsDefaultCalendarLabelsAndBadDatesKeepTheirFallback() {
        val dates = listOf("2026-09-01", "2026-09-02", "2026-09-11")
        assertEquals(trendAxisLabels(dates), trendAxisLabels(dates, day("2026-09-02")..day("2026-09-11")))
        assertEquals(trendAxisLabels(dates), trendAxisLabels(dates, day("2026-09-11")..day("2026-09-01")))
        assertEquals(
            listOf(
                TrendAxisLabel("a", TrendAxisAnchor.START),
                TrendAxisLabel("b", TrendAxisAnchor.CENTER),
                TrendAxisLabel("c", TrendAxisAnchor.END),
            ),
            trendAxisLabels(listOf("a", "b", "c"), day("2026-09-01")..day("2026-09-11")),
        )
    }
}
