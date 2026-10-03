package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class HealthspanPresentationTest {
    @Test fun varyingInputsAndCoverageMatchSwiftOracle() {
        val offsets = (0..119 step 7).toList()
        val rows = listOf(-0.02, 0.0, 0.005, 0.02).map { slope ->
            row(offsets.map { HealthspanPresentation.AgeSample(it, 40 + it * slope) })
        }.toMutableList()
        rows += row(emptyList())
        rows += row(offsets.map { HealthspanPresentation.AgeSample(it, 40.0) }, 20)
        rows += row(offsets.map { HealthspanPresentation.AgeSample(it, 40.0) }, 21, 17.0)
        rows += row(listOf(HealthspanPresentation.AgeSample(0, Double.NaN), HealthspanPresentation.AgeSample(-1, 40.0), HealthspanPresentation.AgeSample(180, 40.0)))
        rows += row(listOf(HealthspanPresentation.AgeSample(15, 40.0), HealthspanPresentation.AgeSample(89, 41.0)))
        rows += HealthspanPresentation.zoneMinutes(listOf(0.0 to 60, 0.999 to 60, 1.0 to 30, 1.999 to 60, 2.0 to 60, 3.0 to 10, null to 60, Double.NaN to 60, -1.0 to 60, 4.0 to 60, 1.0 to -1)).joinToString(",")
        // Standalone Swift stdout, regenerated with HealthspanPresentation.swift and the oracle driver.
        assertEquals("""
            40.0|28|21|5|18
            40.0|10|21|5|18
            40.0|5|21|5|18
            40.0|-8|21|5|18
            nil|nil|21|0|0
            nil|nil|20|5|18
            nil|nil|21|5|18
            nil|nil|21|0|0
            nil|nil|21|1|2
            120,90,70
        """.trimIndent(), rows.joinToString("\n"))
    }

    @Test fun duplicateDaysAndSparseHistoryDoNotInventPace() {
        val result = HealthspanPresentation.snapshot(listOf(HealthspanPresentation.AgeSample(0, 40.0), HealthspanPresentation.AgeSample(0, 39.0)), 21, 40.0)
        assertEquals(39.0, result.age!!, 0.0)
        assertEquals(1, result.historySamples)
        assertNull(result.pace)
    }

    @Test fun paceClampsAndNeedsAdultProfile() {
        for ((slope, expected) in listOf(-0.04 to 30, 0.04 to -10)) {
            val samples = (0..119 step 7).map { HealthspanPresentation.AgeSample(it, 40 + it * slope) }
            assertEquals(expected, HealthspanPresentation.snapshot(samples, 21, 40.0).paceTenths)
            assertNull(HealthspanPresentation.snapshot(samples, 21, Double.NaN).age)
        }
    }

    private fun row(samples: List<HealthspanPresentation.AgeSample>, count: Int = 21, age: Double = 40.0): String {
        val s = HealthspanPresentation.snapshot(samples, count, age)
        return "${s.age?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "nil"}|${s.paceTenths ?: "nil"}|${s.recoveryDays}|${s.recentSamples}|${s.historySamples}"
    }
}
