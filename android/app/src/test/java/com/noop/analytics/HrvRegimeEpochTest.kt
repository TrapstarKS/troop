package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class HrvRegimeEpochTest {
    @Test fun standaloneSwiftOracleAndCalibrationEra() {
        val rows = mutableListOf<String>()
        for (first in listOf(null, -1L, 0L, 86399L, 86400L, 1777672799L, 1777672800L)) {
            for (offset in listOf(-43200L, 0L, 19800L, 50400L)) {
                for (manual in listOf(0.0, 172800.0, 1777680000.0)) {
                    val epoch = Baselines.effectiveHrvEpoch(manual, first, true, offset)
                    rows += java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(epoch)).padStart(16, '0')
                }
            }
        }
        val unchanged = Baselines.effectiveHrvEpoch(172800.0, 1777672800L, false, 0)
        rows += java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(unchanged)).padStart(16, '0')
        for (day in listOf("2026-04-30", "2026-05-01", "2026-05-02", "bad")) {
            rows += if (Baselines.isInHrvEra(day, 1777593600.0)) "1" else "0"
        }
        val keys = listOf("2026-04-29", "2026-04-30", "2026-05-01", "2026-05-02")
        val state = Baselines.foldHistory(listOf(40.0, 41.0, 42.0, 43.0), keys,
            Baselines.metricCfg.getValue("hrv"), 1777593600.0)
        rows += state.nValid.toString()
        // Verbatim actual standalone Swift -O stdout; Swift pins the same literal.
        assertEquals("0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,4105180000000000,0,1,1,0,2", rows.joinToString(","))
        assertEquals(2, state.nValid)
        assertEquals(BaselineStatus.CALIBRATING, state.status)
    }
}
