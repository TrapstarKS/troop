package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryStrainDetailLogicTest {
    @Test
    fun comparisonTargetsAndNumericPresentationMatchSwiftOracle() {
        val keys = listOf("2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03")
        val values = listOf(999.0, 10.0, null, 20.0, Double.NaN, 100.0, 1000.0)
        val targets = listOf(null, "nan", "0.0", "3.99", "4.0", "10.0", "10.01", "21.0")
        val recoveries = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0,
            33.49, 33.999, 34.0, 66.49, 66.999, 67.0, 99.999, 100.0, 101.0)
        val wholeValues = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, -0.0, 0.0, 0.49, 0.5, 1.49, 1.5, 1e300,
            9_223_372_036_854_775_808.0, Math.nextDown(9_223_372_036_854_775_808.0))
        val durations = listOf(-1.0, Double.NaN, 1e300, 59.0, 60.0, 89.0, 90.0, 119.9, 120.0)
        val durationFallbacks: List<Pair<Double?, Double?>> = listOf(null to null, null to 3661.0, 0.0 to 3661.0,
            59.0 to 3661.0, 60.0 to 3661.0, null to -1.0, null to Double.NaN, null to Double.POSITIVE_INFINITY,
            null to 1e300, -1.0 to 3600.0, Double.NaN to 3600.0, Double.POSITIVE_INFINITY to 3600.0, 1e300 to 3600.0)
        val actual = listOf(
            RecoveryStrainDetailLogic.priorMean(keys, values, "2026-09-01", "2026-10-02")!!.toString(),
            targets.joinToString(",") { RecoveryStrainDetailLogic.targetStatus(it, 4, 10).name.lowercase() },
            recoveries.joinToString(",") { RecoveryStrainDetailLogic.recoveryPercent(it)?.toString() ?: "unavailable" },
            wholeValues.joinToString(",") { RecoveryStrainDetailLogic.wholeNumber(it)?.toString() ?: "unavailable" },
            durations.joinToString(",") { RecoveryStrainDetailLogic.durationMinutes(it)?.toString() ?: "unavailable" },
            durationFallbacks.joinToString(",") { (seconds, fallback) -> RecoveryStrainDetailLogic.durationMinutes(seconds, fallback)?.toString() ?: "unavailable" },
        ).joinToString("\n", postfix = "\n")
        val expected = """
        15.0
        unavailable,unavailable,under,under,optimal,optimal,over,over
        unavailable,unavailable,unavailable,unavailable,0,33,33,34,66,66,67,99,100,unavailable
        unavailable,unavailable,unavailable,unavailable,unavailable,0,0,0,1,1,2,unavailable,unavailable,9223372036854774784
        unavailable,unavailable,unavailable,0,1,1,1,1,2
        unavailable,61,0,0,1,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable
        """.trimIndent() + "\n"
        assertEquals(expected, actual)
    }

    @Test
    fun wholeNumberRoundsHalfUpWithinRepresentableRange() {
        val values = listOf(0.0, 0.49, 0.5, 1.49, 1.5, 2.5, Long.MAX_VALUE.toDouble() - 1024)
        assertEquals(listOf(0L, 0L, 1L, 1L, 2L, 3L, 9_223_372_036_854_774_784L),
            values.map(RecoveryStrainDetailLogic::wholeNumber))
    }

    @Test
    fun wholeNumberRejectsMissingMalformedAndUnrepresentableValues() {
        listOf(null, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1e300, Long.MAX_VALUE.toDouble())
            .forEach { assertNull(RecoveryStrainDetailLogic.wholeNumber(it)) }
    }

    @Test
    fun recoveryPercentPreservesSemanticBandsAtEveryThreshold() {
        val scores = listOf(0.0, 33.999999, 34.0, 66.999999, 67.0, 99.999999, 100.0)
        assertEquals(listOf(0, 33, 34, 66, 67, 99, 100), scores.map(RecoveryStrainDetailLogic::recoveryPercent))
        for (step in 0..10_000) {
            val raw = step / 100.0
            val displayed = RecoveryStrainDetailLogic.recoveryPercent(raw)!!.toDouble()
            assertEquals(raw < 34, displayed < 34)
            assertEquals(raw < 67, displayed < 67)
        }
    }

    @Test
    fun recoveryPercentRejectsMissingNonfiniteAndOutOfRangeScores() {
        listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.001, 100.001)
            .forEach { assertNull(RecoveryStrainDetailLogic.recoveryPercent(it)) }
    }

    @Test
    fun priorMeanUsesOnlyFiniteValuesInPriorCalendarWindow() {
        val keys = listOf("2026-09-01", "2026-09-02", "2026-09-15", "2026-09-20", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03")
        val values = listOf(999.0, 20.0, null, Double.NaN, Double.POSITIVE_INFINITY, 40.0, 100.0, 500.0)
        assertEquals(30.0, RecoveryStrainDetailLogic.priorMean(keys, values, "2026-09-02", "2026-10-02")!!, 0.0)
        assertNull(RecoveryStrainDetailLogic.priorMean(keys, values, "2026-10-02", "2026-10-02"))
        assertEquals(20.0, RecoveryStrainDetailLogic.priorMean(keys, listOf(999.0, 20.0), "2026-09-02", "2026-10-02")!!, 0.0)
    }

    @Test
    fun targetIncludesBothBoundariesAndRejectsMissingOrNonfiniteValues() {
        val input = listOf(null, "NaN", "-Infinity", "9.9", "10.0", "12.0", "14.0", "14.1")
        assertEquals(listOf("unavailable", "unavailable", "unavailable", "under", "optimal", "optimal", "optimal", "over"),
            input.map { RecoveryStrainDetailLogic.targetStatus(it, 10, 14).name.lowercase() })
        assertEquals(RecoveryStrainDetailLogic.TargetStatus.Unavailable, RecoveryStrainDetailLogic.targetStatus("12.0", null, 14))
        assertEquals(RecoveryStrainDetailLogic.TargetStatus.Unavailable, RecoveryStrainDetailLogic.targetStatus("12.0", 14, 10))
    }

    @Test
    fun targetStatusMatchesTheVisibleOneDecimalScore() {
        val axisValues = listOf(3.94, 3.99, 4.0, 10.0, 10.01, 10.06)
        val shown = axisValues.map { UnitFormatter.effortDisplay(it / UnitFormatter.EFFORT_SCALE_FACTOR, EffortScale.WHOOP) }
        assertEquals(listOf("3.9", "4.0", "4.0", "10.0", "10.0", "10.1"), shown)
        assertEquals(listOf("under", "optimal", "optimal", "optimal", "optimal", "over"),
            shown.map { RecoveryStrainDetailLogic.targetStatus(it, 4, 10).name.lowercase() })
    }

    @Test
    fun strainPresentationRoundTripsEveryQuarterPointWithoutChangingStoredEffort() {
        for (quarter in 0..400) {
            val effort = quarter / 4.0
            val strain = UnitFormatter.effortValue(effort, EffortScale.WHOOP)
            assertEquals(effort, strain / UnitFormatter.EFFORT_SCALE_FACTOR, 1e-12)
        }
    }
}
