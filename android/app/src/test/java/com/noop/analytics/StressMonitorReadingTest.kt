package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class StressMonitorReadingTest {
    private data class Case(
        val name: String,
        val windows: List<StressMonitorReading.Window>,
        val hasHeartRate: Boolean,
        val now: Long,
        val isToday: Boolean,
        val selection: Long? = null,
    )

    @Test fun matchesTheStandaloneSwiftOracle() {
        val scored = StressMonitorReading.Window(32_400L, 32_699L, 1.5)
        val gap = StressMonitorReading.Window(36_000L, 36_299L, null)
        val active = gap.copy(maskedForActivity = true)
        val cases = listOf(
            Case("partial", listOf(scored), true, 32_699L, true),
            Case("fresh900", listOf(scored), true, 33_599L, true),
            Case("delayed901", listOf(scored), true, 33_600L, true),
            Case("historical", listOf(scored), true, 86_400L, false),
            Case("recentHrUnscored", listOf(scored, gap), true, 36_300L, true),
            Case("selectedOld", listOf(scored, gap), true, 36_300L, true, 32_400L),
            Case("noHr", emptyList(), false, 36_300L, true),
            Case("nightOnly", emptyList(), true, 18_000L, true),
            Case("under300", listOf(gap), true, 36_300L, true),
            Case("activity", listOf(active), true, 36_300L, true),
            Case("selectedActivity", listOf(scored, active), true, 36_300L, true, 36_000L),
            Case("selectedGap", listOf(scored, gap), true, 36_300L, true, 36_000L),
            Case("selectedMissing", listOf(scored), true, 36_300L, true, 1L),
            Case("invalid", listOf(
                StressMonitorReading.Window(36_000L, 36_299L, Double.NaN),
                StressMonitorReading.Window(37_800L, 38_000L, 3.1),
            ), true, 38_000L, true),
            Case("unsorted", listOf(StressMonitorReading.Window(36_000L, 36_299L, 2.0), scored), true, 36_300L, true),
        )
        val actual = cases.joinToString("\n") {
            val reading = StressMonitorReading.resolve(it.windows, it.hasHeartRate, it.now, it.isToday, it.selection)
            val stamp = reading.window?.startTs?.toString() ?: "nil"
            val score = reading.window?.level?.let { level -> String.format(Locale.US, "%.1f", level) } ?: "nil"
            "${it.name}|${reading.state.rawValue}|$stamp|$score"
        } + "\n"
        // Verbatim stdout from StressMonitorReading.swift compiled standalone with swiftc -O.
        val expected = """
            partial|recorded|32400|1.5
            fresh900|recorded|32400|1.5
            delayed901|delayed|32400|1.5
            historical|recorded|32400|1.5
            recentHrUnscored|delayed|32400|1.5
            selectedOld|recorded|32400|1.5
            noHr|noHeartRate|nil|nil
            nightOnly|noWakingHeartRate|nil|nil
            under300|insufficientSamples|nil|nil
            activity|activityExcluded|nil|nil
            selectedActivity|activityExcluded|36000|nil
            selectedGap|insufficientSamples|36000|nil
            selectedMissing|insufficientSamples|nil|nil
            invalid|insufficientSamples|nil|nil
            unsorted|recorded|36000|2.0
        """.trimIndent() + "\n"
        assertEquals(expected, actual)
    }
}
