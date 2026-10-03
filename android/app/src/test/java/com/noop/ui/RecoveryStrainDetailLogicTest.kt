package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryStrainDetailLogicTest {
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
