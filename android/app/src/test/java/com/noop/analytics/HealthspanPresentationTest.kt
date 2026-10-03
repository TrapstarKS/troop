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
        // Expected rows copied verbatim from the standalone Swift fixture stdout.
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

    @Test fun stepsSelectionMatchesSwiftOracle() {
        fun sample(day: String, count: Double, source: String) =
            HealthspanPresentation.StepSample(day, count, source)
        val invalid = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).map {
            sample("2026-02-04", it, "invalid")
        }
        val rows = listOf(
            stepRow("empty", emptyList(), emptyList()),
            stepRow("lower", listOf(sample("2026-02-01", 1.0, "measured")), listOf(
                sample("2026-01-31", 100.0, "too-old"),
                sample("2026-02-05", 100.0, "future"),
            )),
            stepRow("upper", emptyList(), listOf(
                sample("2026-02-04", 4.5, "upper-import"),
                sample("2026-02-05", 100.0, "future"),
            )),
            stepRow("zero", listOf(sample("2026-02-04", 0.0, "measured-zero")), listOf(
                sample("2026-02-04", 1000.0, "imported"),
            )),
            stepRow("maximum", emptyList(), listOf(
                sample("2026-02-04", 10.0, "first-import"),
                sample("2026-02-04", 100.0, "maximum-import"),
                sample("2026-02-04", 50.0, "last-import"),
            )),
            stepRow("invalid", invalid, invalid + sample("2026-02-04", 7.5, "valid-import")),
            stepRow("all-invalid", invalid, invalid),
            stepRow("newer-import", listOf(sample("2026-02-03", 300.0, "older-measured")), listOf(
                sample("2026-02-04", 20.0, "newer-import"),
            )),
            stepRow("import-tie", emptyList(), listOf(
                sample("2026-02-04", 7.0, "first-import"),
                sample("2026-02-04", 7.0, "second-import"),
            )),
            stepRow("measured-first", listOf(
                sample("2026-02-04", 5.0, "first-measured"),
                sample("2026-02-04", 6.0, "second-measured"),
            ), listOf(sample("2026-02-04", 100.0, "imported"))),
            stepRow("reversed-bounds", emptyList(), listOf(sample("2026-02-02", 1.0, "imported")),
                    "2026-02-04", "2026-02-01"),
        )
        // Expected rows copied verbatim from the standalone Swift fixture stdout.
        assertEquals("""
            empty|nil
            lower|2026-02-01|1.0|measured
            upper|2026-02-04|4.5|upper-import
            zero|2026-02-04|0.0|measured-zero
            maximum|2026-02-04|100.0|maximum-import
            invalid|2026-02-04|7.5|valid-import
            all-invalid|nil
            newer-import|2026-02-04|20.0|newer-import
            import-tie|2026-02-04|7.0|first-import
            measured-first|2026-02-04|5.0|first-measured
            reversed-bounds|nil
        """.trimIndent() + "\n", rows.joinToString("\n") + "\n")
    }

    private fun stepRow(name: String, measured: List<HealthspanPresentation.StepSample>,
                        imported: List<HealthspanPresentation.StepSample>,
                        fromDay: String = "2026-02-01", throughDay: String = "2026-02-04"): String {
        val selected = HealthspanPresentation.latestSteps(measured, imported, fromDay, throughDay)
            ?: return "$name|nil"
        return "$name|${selected.day}|${selected.count}|${selected.source}"
    }

    private fun row(samples: List<HealthspanPresentation.AgeSample>, count: Int = 21, age: Double = 40.0): String {
        val s = HealthspanPresentation.snapshot(samples, count, age)
        return "${s.age?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "nil"}|${s.paceTenths ?: "nil"}|${s.recoveryDays}|${s.recentSamples}|${s.historySamples}"
    }
}
