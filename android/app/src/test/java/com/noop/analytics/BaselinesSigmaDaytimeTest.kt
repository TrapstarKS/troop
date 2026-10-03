package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.max

/**
 * Kotlin twin of the sigma() + daytime-config additions in BaselinesTests.swift (PR #413).
 * Pure-function tests; no DB.
 */
class BaselinesSigmaDaytimeTest {

    /**
     * sigma() is a pure extraction of deviation()'s internal conversion — must match it exactly
     * and stay internally consistent (deviation at baseline + sigma is z == 1.0).
     */
    @Test
    fun sigmaMatchesDeviationsInternalConversion() {
        val s = Baselines.foldHistory(List(14) { 50.0 }, Baselines.hrvCfg)
        val sigma = Baselines.sigma(s)
        assertEquals(max(1.253 * s.spread, 1e-9), sigma, 1e-9)
        val dev = Baselines.deviation(s.baseline + sigma, s)
        assertEquals(1.0, dev.z, 1e-6)
    }

    /**
     * The new daytime_hr / daytime_rmssd configs exist, are reachable via both the map and the
     * convenience accessor, and are distinct from the nightly resting_hr/hrv configs they sit
     * alongside (different bounds/floor — daytime HR runs warmer than nocturnal RHR).
     */
    @Test
    fun daytimeConfigsExistAndAreDistinctFromNightlyConfigs() {
        assertEquals(Baselines.daytimeHRCfg, Baselines.metricCfg["daytime_hr"])
        assertEquals(Baselines.daytimeRMSSDCfg, Baselines.metricCfg["daytime_rmssd"])
        assertNotEquals(Baselines.daytimeHRCfg, Baselines.restingHRCfg)
        assertNotEquals(Baselines.daytimeRMSSDCfg, Baselines.hrvCfg)
    }
    @Test
    fun roundedDelta2dpMatchesStandaloneSwiftOracle() {
        val state = Baselines.foldHistory(List(14) { 34.0 }, Baselines.metricCfg.getValue("skin_temp"))
        val offsets = listOf(-8.0, -1.0, -0.505, -0.5, -0.125, -0.005, 0.0, 0.005, 0.125, 0.5, 0.505, 1.0, 8.0)
        val output = offsets.joinToString("\n") { offset ->
            val value = 34.0 + offset
            "%016x:%016x".format(java.lang.Double.doubleToRawLongBits(value),
                java.lang.Double.doubleToRawLongBits(Baselines.roundedDelta2dp(value, state)))
        }
        // Verbatim stdout from swiftc -O Baselines.swift main.swift, pinned by the Swift twin test.
        assertEquals("""
            403a000000000000:c020000000000000
            4040800000000000:bff0000000000000
            4040bf5c28f5c28f:bfe051eb851eb852
            4040c00000000000:bfe0000000000000
            4040f00000000000:bfc0a3d70a3d70a4
            4040ff5c28f5c28f:bf847ae147ae147b
            4041000000000000:0000000000000000
            404100a3d70a3d71:3f847ae147ae147b
            4041100000000000:3fc0a3d70a3d70a4
            4041400000000000:3fe0000000000000
            404140a3d70a3d71:3fe051eb851eb852
            4041800000000000:3ff0000000000000
            4045000000000000:4020000000000000
        """.trimIndent(), output)
    }
}
