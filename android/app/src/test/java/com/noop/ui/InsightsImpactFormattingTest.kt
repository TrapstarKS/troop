package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsImpactFormattingTest {
    @Test
    fun smallChangesKeepDirectionAndRoundingMatchesSwiftOracle() {
        val cases = listOf(0.0, -0.0, 0.00001, -0.00001, 0.49, -0.49, 0.5, -0.5, 0.99, -0.99, 1.0, -1.0, 1.4999999999999998, -1.4999999999999998, 1.5, -1.5, 15.5, -15.5, 100.0, -100.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        assertEquals("""
            0%
            0%
            +<1%
            −<1%
            +<1%
            −<1%
            +<1%
            −<1%
            +<1%
            −<1%
            +1%
            −1%
            +1%
            −1%
            +2%
            −2%
            +16%
            −16%
            +100%
            −100%
            —
            —
            —
        """.trimIndent(), cases.joinToString("\n", transform = InsightsImpactFormatting::percentage))
    }
}
