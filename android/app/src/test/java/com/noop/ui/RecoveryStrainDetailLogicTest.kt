package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryStrainDetailLogicTest {
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
        val input = listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, 9.9, 10.0, 12.0, 14.0, 14.1)
        assertEquals(listOf("unavailable", "unavailable", "unavailable", "under", "optimal", "optimal", "optimal", "over"),
            input.map { RecoveryStrainDetailLogic.targetStatus(it, 10, 14).name.lowercase() })
        assertEquals(RecoveryStrainDetailLogic.TargetStatus.Unavailable, RecoveryStrainDetailLogic.targetStatus(12.0, null, 14))
        assertEquals(RecoveryStrainDetailLogic.TargetStatus.Unavailable, RecoveryStrainDetailLogic.targetStatus(12.0, 14, 10))
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
