package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScoreValueTest {
    @Test
    fun invalidImportedScoresRemainUnavailable() {
        val values = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, 101.0, 1e100, 0.0, 33.5, 34.0, 66.5, 67.0, 100.0)
        // Verbatim optimized Swift HomeScoreValue oracle; score storage and formulas are unchanged.
        val actual = values.joinToString("\n") { homeScoreValue(it)?.toString() ?: "nil" }
        assertEquals("nil\nnil\nnil\nnil\nnil\nnil\nnil\n0.0\n33.5\n34.0\n66.5\n67.0\n100.0", actual)
    }
}
