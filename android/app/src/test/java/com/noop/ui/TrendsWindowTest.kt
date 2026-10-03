package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsWindowTest {
    @Test fun windowsMatchTheSwiftCalendarOracle() {
        val cases = listOf(
            Triple(7, 0, "2026-10-02"), Triple(7, -1, "2026-10-02"), Triple(7, 1, "2026-10-02"),
            Triple(7, 0, "2025-01-01"), Triple(30, -1, "2024-03-01"), Triple(30, 0, "2024-02-29"),
            Triple(30, -2, "2025-01-31"), Triple(180, 0, "2026-01-01"), Triple(180, -1, "2026-10-02"),
            Triple(30, 0, "2024-02-30"), Triple(7, 0, "invalid"),
        )
        val lines = cases.map { (days, offset, today) ->
            val window = TrendsWindow.period(days, offset, today)
            "$days|$offset|$today|${window?.let { "${it.start}|${it.end}" } ?: "nil"}"
        } + listOf(7, 30, 180).map { days ->
            "min|$days|${TrendsWindow.minimumOffset(days, "2026-03-01", "2026-10-02")}"
        }
        assertEquals(
            """
            7|0|2026-10-02|2026-09-28|2026-10-02
            7|-1|2026-10-02|2026-09-21|2026-09-27
            7|1|2026-10-02|2026-09-28|2026-10-02
            7|0|2025-01-01|2024-12-30|2025-01-01
            30|-1|2024-03-01|2024-02-01|2024-02-29
            30|0|2024-02-29|2024-02-01|2024-02-29
            30|-2|2025-01-31|2024-11-01|2024-11-30
            180|0|2026-01-01|2025-08-01|2026-01-01
            180|-1|2026-10-02|2025-11-01|2026-04-30
            30|0|2024-02-30|nil
            7|0|invalid|nil
            min|7|-31
            min|30|-7
            min|180|-1
            """.trimIndent(),
            lines.joinToString("\n"),
        )
    }

    @Test fun inclusiveBoundsExcludeFutureAndOtherPeriods() {
        val current = TrendsWindow.period(7, 0, "2026-10-02")!!
        assertTrue(current.contains("2026-10-02"))
        assertFalse(current.contains("2026-10-03"))
        assertFalse(current.contains("2026-09-27"))
        assertEquals(0, TrendsWindow.minimumOffset(30, null, "2026-10-02"))
        for (days in listOf(7, 30, 180)) {
            val minimum = TrendsWindow.minimumOffset(days, "2026-03-01", "2026-10-02")
            assertTrue(TrendsWindow.period(days, minimum, "2026-10-02")!!.contains("2026-03-01"))
        }
    }
}
