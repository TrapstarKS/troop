package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthspanHistoryTest {
    @Test fun calibrationHistoryAndContributorsMatchActualSwiftOracle() {
        assertEquals("""
            one-fresh|initialCalibration|21|21
            age-one-fresh|initialCalibration|nil|nil
            initial-89|initialCalibration|89|21
            age-initial-89|initialCalibration|nil|nil
            initial-90|ready|90|21
            age-initial-90|ready|4044000000000000|nil
            lost-recent|recentCoverage|120|20
            age-lost-recent|recentCoverage|nil|nil
            recovered|ready|120|21
            age-recovered|ready|4044000000000000|nil
            stale|unavailable|120|21
            age-stale|unavailable|nil|nil
            missing|unavailable|120|21
            age-missing|unavailable|nil|nil
            minor|adultOnly|120|31
            age-minor|adultOnly|nil|nil
            invalid-profile|adultOnly|120|31
            age-invalid-profile|adultOnly|nil|nil
            duplicate|ready|90|21
            age-duplicate|ready|4044000000000000|nil
            empty|initialCalibration|0|0
            age-empty|initialCalibration|nil|nil
            history-0|0|initialCalibration|0|0
            history-1|0|initialCalibration|1|1
            history-31|0|initialCalibration|31|31
            history-89|58|initialCalibration|31|31
            history-90|59|initialCalibration|31|31
            history-800|769|initialCalibration|31|31
            history-1000|969|initialCalibration|31|31
            history-4000|3969|initialCalibration|31|31
            history-4100|3969|initialCalibration|31|31
            compare-0-false|nil|nil|0|0
            compare-0-true|nil|nil|0|0
            select-0-30|nil
            select-0-180|nil
            compare-1-false|100|100|1|1
            compare-1-true|100|100|1|1
            select-1-30|0|4024000000000000
            select-1-180|0|4024000000000000
            compare-2-false|250|350|2|4
            compare-2-true|250|467|2|3
            select-2-30|29|403e000000000000
            select-2-180|30|4044000000000000
            compare-3-false|0|0|1|1
            compare-3-true|0|0|1|1
            select-3-30|4|0
            select-3-180|4|0
            compare-4-false|48|298|30|180
            compare-4-true|290|2065|5|26
            select-4-30|29|4023555555555555
            select-4-180|31|4024aaaaaaaaaaab
            compare-5-false|9|9|3|3
            compare-5-true|9|9|3|3
            select-5-30|29|0
            select-5-180|29|0
            activity-0|0:4042000000000000,7:0,179:4024000000000000
            activity-1|0:4028000000000000,7:403e000000000000,179:0
            activity-2|0:4034000000000000,7:403e000000000000
            strength|Strength|my-whoop|true
            strength|Traditional Strength Training|apple-health|true
            strength|functional_strength-training|health-connect|true
            strength|Walking|lifting|true
            strength|Walking|my-whoop|false
            strength|Yoga|apple-health|false
        """.trimIndent() + "\n", healthspanHistoryOracleRows())
    }
}


private fun healthspanHistoryOracleRows(): String {
    val rows = mutableListOf<String>()
    data class Case(val name: String, val offsets: List<Int>, val age: Double, val latest: Int?)
    val cases = listOf(
        Case("one-fresh", (0 until 21).toList(), 40.0, 0),
        Case("initial-89", (0 until 21).toList() + 88, 40.0, 0),
        Case("initial-90", (0 until 21).toList() + 89, 40.0, 0),
        Case("lost-recent", (0 until 20).toList() + 119, 40.0, 0),
        Case("recovered", (0 until 21).toList() + 119, 40.0, 0),
        Case("stale", (0 until 21).toList() + 119, 40.0, 15),
        Case("missing", (0 until 21).toList() + 119, 40.0, null),
        Case("minor", (0 until 31).toList() + 119, 17.0, 0),
        Case("invalid-profile", (0 until 31).toList() + 119, Double.NaN, 0),
        Case("duplicate", (0 until 21).toList() + (0 until 21).toList() + listOf(89, -1, 4000), 18.0, 14),
        Case("empty", emptyList(), 40.0, 0),
    )
    for ((name, offsets, age, latest) in cases) {
        val e = HealthspanHistory.eligibility(offsets, age, latest)
        rows += "$name|${e.state.name}|${e.initialDays}|${e.recoveryDays}"
        val ageSamples = latest?.let { listOf(HealthspanPresentation.AgeSample(it, 40.0)) }.orEmpty()
        val snapshot = HealthspanPresentation.snapshot(ageSamples, offsets, age)
        rows += "age-$name|${snapshot.eligibility.state.name}|${snapshot.age?.let { java.lang.Double.doubleToRawLongBits(it).toString(16) } ?: "nil"}|${snapshot.paceTenths ?: "nil"}"
    }
    for (count in listOf(0, 1, 31, 89, 90, 800, 1000, 4000, 4100)) {
        val offsets = (0 until count).toList()
        val bound = HealthspanHistory.oldestReferenceOffset(offsets)
        val e = HealthspanHistory.eligibility(offsets.filter { it in 0 until 4000 }.map { it - bound }, 40.0, 0)
        rows += "history-$count|$bound|${e.state.name}|${e.initialDays}|${e.recoveryDays}"
    }
    val sets = listOf(emptyList(), listOf(HealthspanHistory.Sample(0, 10.0)),
        listOf(HealthspanHistory.Sample(0, 10.0), HealthspanHistory.Sample(0, 20.0), HealthspanHistory.Sample(29, 30.0), HealthspanHistory.Sample(30, 40.0), HealthspanHistory.Sample(179, 50.0), HealthspanHistory.Sample(180, 100.0)),
        listOf(HealthspanHistory.Sample(-1, 10.0), HealthspanHistory.Sample(1, Double.NaN), HealthspanHistory.Sample(2, Double.POSITIVE_INFINITY), HealthspanHistory.Sample(3, -1.0), HealthspanHistory.Sample(4, 0.0)),
        (0 until 180).map { HealthspanHistory.Sample(it, it.toDouble() / 3) },
        listOf(HealthspanHistory.Sample(7, 1.25), HealthspanHistory.Sample(14, 1.35), HealthspanHistory.Sample(29, 0.0)),
    )
    for ((index, samples) in sets.withIndex()) {
        for (weekly in listOf(false, true)) {
            val c = HealthspanHistory.comparison(samples, weekly)
            rows += "compare-$index-$weekly|${c.recentTenths ?: "nil"}|${c.longTermTenths ?: "nil"}|${c.recentCount}|${c.longTermCount}"
        }
        for (range in listOf(30, 180)) {
            val p = HealthspanHistory.selected(samples, 31, range)
            rows += "select-$index-$range|" + (p?.let { "${it.daysAgo}|${java.lang.Double.doubleToRawLongBits(it.value).toString(16)}" } ?: "nil")
        }
    }
    val activities = listOf(
        HealthspanHistory.Activity(0, 3600.0, listOf(10.0,20.0,30.0,15.0,5.0), false),
        HealthspanHistory.Activity(0, 1200.0, null, true),
        HealthspanHistory.Activity(7, 1800.0, listOf(0.0,0.0,0.0,0.0,100.0), true),
        HealthspanHistory.Activity(179, 600.0, listOf(100.0,0.0,0.0,0.0,0.0), false),
        HealthspanHistory.Activity(180, 9999.0, listOf(100.0,0.0,0.0,0.0,0.0), true),
        HealthspanHistory.Activity(1, Double.NaN, listOf(10.0,20.0,30.0,15.0,5.0), true),
        HealthspanHistory.Activity(2, -1.0, listOf(10.0,20.0,30.0,15.0,5.0), true),
        HealthspanHistory.Activity(3, 600.0, listOf(Double.NaN,0.0,0.0,0.0,0.0), false),
        HealthspanHistory.Activity(4, 600.0, listOf(0.0,0.0,0.0,0.0,0.0), false),
    )
    for ((index, points) in HealthspanHistory.activitySeries(activities).withIndex()) {
        rows += "activity-$index|" + points.joinToString(",") { "${it.daysAgo}:${java.lang.Double.doubleToRawLongBits(it.value).toString(16)}" }
    }
    for ((sport, source) in listOf("Strength" to "my-whoop", "Traditional Strength Training" to "apple-health", "functional_strength-training" to "health-connect", "Walking" to "lifting", "Walking" to "my-whoop", "Yoga" to "apple-health")) {
        rows += "strength|$sport|$source|${HealthspanHistory.isStrength(sport, source)}"
    }
    return rows.joinToString("\n") + "\n"
}
