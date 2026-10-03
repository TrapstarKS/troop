package com.noop.analytics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins every display status and the exact range bits to the standalone optimized Swift oracle. */
class HealthMonitorAssessmentTest {
    @Test
    fun `matches standalone Swift oracle`() {
        val trusted: List<Double?> = List(14) { 35.0 }
        val boundaryCfg = MetricCfg(-100.0, 100.0, 1.0, 14.0, 21.0)
        val zeroHistory: List<Double?> = List(14) { 0.0 }
        val sigma = 1.253
        val oxygen = HealthMonitorAssessment.bloodOxygenCfg
        val rows = mutableListOf<String>()

        fun bits(value: Double?): String = value?.let { java.lang.Long.toHexString(it.toRawBits()) } ?: "nil"
        fun record(
            name: String, value: Double?, history: List<Double?>,
            cfg: MetricCfg = Baselines.hrvCfg, verified: Boolean = true,
        ) {
            val result = HealthMonitorAssessment.assess(value, history, cfg, verified)
            rows.add("$name|${result.status.raw}|${bits(result.lower)}|${bits(result.upper)}|${result.nights}")
        }

        record("missing", null, trusted)
        record("nan", Double.NaN, trusted)
        record("positiveInfinity", Double.POSITIVE_INFINITY, trusted)
        record("negativeInfinity", Double.NEGATIVE_INFINITY, trusted)
        record("missingUnverified", null, trusted, verified = false)
        record("unverified", 35.0, trusted, verified = false)
        record("implausibleLow", 4.0, trusted)
        record("implausibleHigh", 251.0, trusted)
        record("noHistory", 35.0, emptyList())
        for (nights in listOf(1, 3, 4, 13, 14)) {
            record("nights$nights", 35.0, List(nights) { 35.0 })
        }
        record("missingHistory", 35.0, List(20) { null })
        record("mixedHistory", 35.0,
            listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4.0, 251.0) + trusted)
        record("thirteenWithNan", 35.0, List(13) { 35.0 } + listOf(Double.NaN))
        record("gap14", 35.0, trusted + List(14) { null })
        record("gap15", 35.0, trusted + List(15) { null })
        record("nonfiniteGap15", 35.0, trusted + List(15) { Double.POSITIVE_INFINITY })
        record("resumed", 35.0, trusted + List(15) { null } + listOf(35.0))
        for (multiplier in listOf(-3.000001, -3.0, -2.000001, -2.0, 0.0, 2.0, 2.000001, 3.0, 3.000001)) {
            record("z${String.format(Locale.ROOT, "%.6f", multiplier)}",
                multiplier * sigma, zeroHistory, boundaryCfg)
        }
        record("oxygenCalibrating", 98.0, List(13) { 98.0 }, oxygen)
        record("oxygenWithin", 98.0, List(14) { 98.0 }, oxygen)
        record("oxygenOutside", 96.5, List(14) { 98.0 }, oxygen)
        record("oxygenFarOutside", 95.0, List(14) { 98.0 }, oxygen)
        record("oxygenClippedHigh", 100.0, List(14) { 100.0 }, oxygen)
        record("oxygenClippedLow", 70.0, List(14) { 70.0 }, oxygen)
        record("oxygenImplausible", 69.0, List(14) { 98.0 }, oxygen)
        record("skinDeviation", 0.2, List(14) { 0.2 }, VitalBands.skinTempDeviationCfg)
        record("varyingHistory", 38.0, (0 until 30).map { (30 + (it * 7) % 11).toDouble() })

        assertEquals(39, rows.size)
        assertEquals(SWIFT.trimIndent() + "\n", rows.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val SWIFT = """
missing|unavailable|nil|nil|0
nan|unavailable|nil|nil|0
positiveInfinity|unavailable|nil|nil|0
negativeInfinity|unavailable|nil|nil|0
missingUnverified|unavailable|nil|nil|0
unverified|unverified|nil|nil|14
implausibleLow|unverified|nil|nil|14
implausibleHigh|unverified|nil|nil|14
noHistory|calibrating|nil|nil|0
nights1|calibrating|nil|nil|1
nights3|calibrating|nil|nil|3
nights4|calibrating|nil|nil|4
nights13|calibrating|nil|nil|13
nights14|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
missingHistory|calibrating|nil|nil|0
mixedHistory|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
thirteenWithNan|calibrating|nil|nil|13
gap14|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
gap15|calibrating|nil|nil|14
nonfiniteGap15|calibrating|nil|nil|14
resumed|withinRange|40367851eb851eb8|4047c3d70a3d70a4|15
z-3.000001|farOutsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-3.000000|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-2.000001|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-2.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z0.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z2.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z2.000001|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z3.000000|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z3.000001|farOutsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
oxygenCalibrating|calibrating|nil|nil|13
oxygenWithin|withinRange|40582fced916872b|4058d03126e978d5|14
oxygenOutside|outsideRange|40582fced916872b|4058d03126e978d5|14
oxygenFarOutside|farOutsideRange|40582fced916872b|4058d03126e978d5|14
oxygenClippedHigh|withinRange|4058afced916872b|4059000000000000|14
oxygenClippedLow|withinRange|4051800000000000|4051d03126e978d5|14
oxygenImplausible|unverified|nil|nil|14
skinDeviation|withinRange|bfe1a858793dd97e|3fee7525460aa64c|14
varyingHistory|withinRange|40367c0179714b7c|4047c5aed1338706|30
"""
    }
}
