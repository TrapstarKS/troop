package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

class HealthSignalReliabilityTest {
    @Test fun freshComputedProvenanceAndImportedPrecedence() {
        assertNull(HealthSignalReliability.hrv(50.0, computed = true))
        assertNull(HealthSignalReliability.hrv(50.0, computed = true, freshScoringValid = 0.0))
        assertNull(HealthSignalReliability.hrv(50.0, computed = true, freshScoringValid = 1.0, overcount = 1.0))
        assertEquals(50.0, HealthSignalReliability.hrv(50.0, computed = true, freshScoringValid = 1.0)!!, 0.0)
        assertEquals(50.0, HealthSignalReliability.hrv(50.0, computed = false, freshScoringValid = 0.0, overcount = 1.0)!!, 0.0)
        assertNull(HealthSignalReliability.hrv(Double.NaN, computed = false))
    }

    @Test fun calendarGapsAndRecalibrationCannotBorrowTrust() {
        val keys = HealthSignalReliability.dayKeys("2026-02-02", 28, 3)
        val values = keys.map { if (it >= "2026-01-27") 60.0 else null }
        val provisional = Baselines.foldHistory(values, keys, Baselines.hrvCfg, 0.0)
        assertTrue(provisional.usable)
        assertFalse(provisional.trusted)
        assertTrue(Baselines.foldHistory(List(keys.size) { 60.0 }, keys, Baselines.hrvCfg, 0.0).trusted)
        assertFalse(Baselines.foldHistory(List(keys.size) { 60.0 }, keys, Baselines.hrvCfg, 1769947200.0).trusted)
    }

    @Test fun matchesStandaloneSwiftEligibilityOracle() {
        val values = listOf(null, 4.0, 5.0, 50.0, 250.0, 251.0, Double.NaN, Double.POSITIVE_INFINITY)
        val markers = listOf(null, 0.0, 1.0, Double.NaN)
        val actual = buildString {
            for (computed in listOf(false, true)) for (fresh in markers) for (overcount in markers) for (value in values)
                append(if (HealthSignalReliability.hrv(value, computed, fresh, overcount) == null) '0' else '1')
        }
        assertEquals("0011100000111000001110000011100000111000001110000011100000111000001110000011100000111000001110000011100000111000001110000011100000000000000000000000000000000000000000000000000000000000000000000011100000111000000000000000000000000000000000000000000000000000", actual)
    }

    @Test fun matchesStandaloneSwiftCalendarOracle() {
        val actual = listOf(
            HealthSignalReliability.dayKeys("2024-03-01", 4),
            HealthSignalReliability.dayKeys("2026-01-03", 5, 1),
            HealthSignalReliability.dayKeys("bad", 4),
            HealthSignalReliability.dayKeys("2026-02-30", 4),
            HealthSignalReliability.dayKeys("2026-01-04", 4, 0, 1767355200.0),
        ).joinToString("\n") { it.joinToString(",") }
        assertEquals("2024-02-27,2024-02-28,2024-02-29,2024-03-01\n2025-12-29,2025-12-30,2025-12-31,2026-01-01,2026-01-02\n\n\n2026-01-03,2026-01-04", actual)
    }
    @Test fun matchesStandaloneSwiftValueBoundRecordOracle() {
        val actual = buildString {
            for (stored in listOf(4.0, 50.0, Double.NaN, Double.POSITIVE_INFINITY))
                for (eligible in listOf(false, true))
                    for (current in listOf(null, 4.0, 50.0, Double.NaN, Double.POSITIVE_INFINITY))
                        append(if (HealthSignalReliability.Record(stored, eligible).matches(current)) '1' else '0')
        }
        assertEquals("0000001000000000010000000000000000000000", actual)
    }

    @Test fun matchesStandaloneSwiftPhysicalSourceOwnerOracle() {
        val sources = listOf("import", "active", "canonical")
        val values = listOf(60.0, 40.0, 20.0)
        val actual = buildString {
            for (order in listOf(sources, sources.reversed())) for (present in 0 until 8) for (eligible in 0 until 8) {
                val bySource = sources.indices.filter { present and (1 shl it) != 0 }.associate { index ->
                    sources[index] to HealthSignalReliability.Record(values[index], eligible and (1 shl index) != 0)
                }
                for (current in listOf(20.0, 40.0, 60.0)) {
                    val record = HealthSignalReliability.firstRecord(order, bySource)
                    append(if (record == null) '0' else '1')
                    append(if (record?.eligible == true) '1' else '0')
                    append(if (record?.matches(current) == true) '1' else '0')
                }
            }
        }
        assertEquals("000000000000000000000000000000000000000000000000000000000000000000000000100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100100100100100100100111110110111110110111110110111110110100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100110110111100100100110110111100100100110110111100100100110110111000000000000000000000000000000000000000000000000000000000000000000000000100100100110110111100100100110110111100100100110110111100100100110110111100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100110111110110111110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110100100100100100100100100100100100100111110110111110110111110110111110110", actual)
    }

    @Test fun matchesStandaloneSwiftIndependentRespiratoryEligibilityOracle() {
        assertNull(HealthSignalReliability.hrv(50.0, true, 0.0, 1.0))
        assertEquals(16.0, HealthSignalReliability.respiration(16.0, true, 1.0)!!, 0.0)
        assertEquals(16.0, HealthSignalReliability.respiration(16.0, false, 0.0)!!, 0.0)
        val actual = buildString {
            for (computed in listOf(false, true)) for (fresh in listOf(null, 0.0, 1.0, Double.NaN))
                for (value in listOf(null, 7.0, 8.0, 16.0, 25.0, 26.0, Double.NaN, Double.POSITIVE_INFINITY))
                    append(if (HealthSignalReliability.respiration(value, computed, fresh) == null) '0' else '1')
        }
        assertEquals("0111110001111100011111000111110000000000000000000111110000000000", actual)
    }

}
