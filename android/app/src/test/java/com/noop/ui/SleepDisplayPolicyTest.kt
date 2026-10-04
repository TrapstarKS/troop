package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepDisplayPolicyTest {
    @Test fun displayPolicyMatchesOptimizedSwiftOracle() {
        val inputs: List<Pair<String, List<Double?>>> = listOf(
            "stored-stage-disagreement" to listOf(420.0, 450.0, 480.0, 100.0, null, 88.0),
            "fragmented" to listOf(240.0, 255.0, 420.0, 420.0 / 450.0 * 100.0, null, 96.0),
            "imported-need-without-day" to listOf(390.0, 400.0, null, null, 450.0, 95.0),
            "all-awake" to listOf(0.0, 60.0, 480.0, 100.0, null, 90.0),
            "import-only-summary" to listOf(null, null, 420.0, 420.0 / 450.0 * 100.0, 480.0, 0.88),
            "daily-fallback" to listOf(null, null, 420.0, 420.0 / 450.0 * 100.0, null, 88.0),
            "missing-asleep" to listOf(null, null, null, null, 450.0, 88.0),
            "zero-daily" to listOf(null, null, 0.0, null, 450.0, null),
            "empty-recorded" to listOf(0.0, 0.0, 420.0, 420.0 / 450.0 * 100.0, null, 0.88),
            "imported-need-wins" to listOf(420.0, 450.0, 480.0, 100.0, 600.0, 88.0),
            "missing-need" to listOf(390.0, 400.0, null, 0.0, null, 95.0),
            "nonfinite-recorded" to listOf(Double.NaN, Double.POSITIVE_INFINITY, 420.0, 420.0 / 450.0 * 100.0, 450.0, 0.9),
            "invalid-recorded" to listOf(450.0, 400.0, 420.0, 0.0, 0.0, 0.88),
            "missing" to listOf(null, null, null, null, null, null),
        )
        val actual = inputs.joinToString("\n") { (name, input) ->
            val value = SleepDisplayAmounts.resolve(input[0], input[1], input[2], input[3], input[4], input[5])
            (listOf(name) + listOf(value.asleepMin, value.needMin, value.sufficiencyPct, value.efficiencyPct).map {
                it?.let { java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(it)).padStart(16, '0') } ?: "null"
            }).joinToString("|")
        }
        // Verbatim optimized Swift stdout; retain exact optional-value and floating-point bits.
        assertEquals("""
stored-stage-disagreement|407a400000000000|407e000000000000|4055e00000000000|4057555555555555
fragmented|406e000000000000|407c200000000000|404aaaaaaaaaaaab|4057878787878787
imported-need-without-day|4078600000000000|407c200000000000|4055aaaaaaaaaaab|4058600000000000
all-awake|0000000000000000|407e000000000000|0000000000000000|0000000000000000
import-only-summary|407a400000000000|407e000000000000|4055e00000000000|4056000000000000
daily-fallback|407a400000000000|407c200000000000|4057555555555555|4056000000000000
missing-asleep|null|407c200000000000|null|4056000000000000
zero-daily|null|407c200000000000|null|null
empty-recorded|407a400000000000|407c200000000000|4057555555555555|4056000000000000
imported-need-wins|407a400000000000|4082c00000000000|4051800000000000|4057555555555555
missing-need|4078600000000000|null|null|4058600000000000
nonfinite-recorded|407a400000000000|407c200000000000|4057555555555555|4056800000000000
invalid-recorded|407a400000000000|null|null|4056000000000000
missing|null|null|null|null
""".trimIndent(), actual)
    }
}
