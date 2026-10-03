package com.noop.ingest

import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopImportSkinTempTest {
    private fun row(day: String, deviation: Double? = null, celsius: Double? = null, device: String = "my-whoop") =
        DailyMetric(deviceId = device, day = day, totalSleepMin = 420.0, efficiency = 0.9,
            deepMin = 80.0, remMin = 100.0, lightMin = 240.0, disturbances = 1, restingHr = 52,
            avgHrv = 68.0, recovery = 72.0, strain = 35.0, exerciseCount = 1, spo2Pct = 97.0,
            skinTempDevC = deviation, respRateBpm = 14.0, steps = 4321, activeKcalEst = 456.0,
            spo2Red = 123, spo2Ir = 234, avgSdnn = 45.0, skinTempC = celsius, sleepHrOnly = true)

    private fun nights(temperatures: List<Double>) = temperatures.mapIndexed { index, temperature ->
        row("2026-07-${(index + 1).toString().padStart(2, '0')}", celsius = temperature)
    }

    @Test
    fun deviationUsesOnlyPriorNightsAndPreservesMissingAbsolute() {
        val source = nights(List(4) { 33.5 } + 34.3)
        val output = WhoopCsvImporter.withSkinTempDeviations(source.reversed())
        assertTrue(output.take(4).all { it.skinTempDevC == null })
        assertEquals(34.3, output.last().skinTempC!!, 0.0)
        assertEquals(0.8, output.last().skinTempDevC!!, 0.0)
        val deviationOnly = row("2026-07-06", deviation = 0.2)
        assertEquals(listOf(deviationOnly), WhoopCsvImporter.withSkinTempDeviations(listOf(deviationOnly)))
    }

    @Test
    fun incrementalAndRepeatedImportUseStoredPriorHistoryOnce() {
        val source = nights(List(4) { 33.5 } + 34.3)
        val prior = WhoopCsvImporter.withSkinTempDeviations(source.take(4))
        val final = WhoopCsvImporter.withSkinTempDeviations(source.takeLast(1), history = prior)
        val repeated = WhoopCsvImporter.withSkinTempDeviations(source.takeLast(1), history = prior + final)
        assertEquals(final, repeated)
        assertEquals(WhoopCsvImporter.withSkinTempDeviations(source), prior + final)
        assertEquals(0.8, final.single().skinTempDevC!!, 0.0)
        val backward = WhoopCsvImporter.withSkinTempDeviations(source.takeLast(1))
        assertEquals(WhoopCsvImporter.withSkinTempDeviations(source), WhoopCsvImporter.withSkinTempDeviations(backward + source.take(4)))
    }

    @Test
    fun duplicateDaysUseLastIncomingRowAndDoNotDoubleSeed() {
        val source = nights(List(4) { 33.5 } + 34.3)
        val duplicate = row("2026-07-01", celsius = 36.0)
        assertEquals(WhoopCsvImporter.withSkinTempDeviations(source), WhoopCsvImporter.withSkinTempDeviations(listOf(duplicate) + source))
        assertEquals(WhoopCsvImporter.withSkinTempDeviations(source), WhoopCsvImporter.withSkinTempDeviations(source, history = source))
    }

    @Test
    fun futureImportsWriteAnExactAbsoluteOriginMarkerForCelsiusAndFahrenheit() {
        for ((header, value) in listOf("Skin temp (celsius)" to "33.1", "Skin temp (f)" to "91.58")) {
            val csv = "Cycle start time,Cycle end time,Cycle timezone,$header\n2026-07-01 00:00:00,,UTC+00:00,$value"
            val table = CsvTable.fromData(csv.toByteArray())
            val daily = WhoopCsvImporter.parseCycles(table, "my-whoop").single()
            val marker = WhoopCsvImporter.parseCycleSeries(table, "my-whoop").single()
            assertEquals(MetricSeriesRow("my-whoop", daily.day, "skin_temp", daily.skinTempC!!), marker)
        }
    }

    @Test
    fun repairChangesOnlyLegacyCandidatesInCanonicalImportNamespace() {
        val legacy = nights(List(4) { 33.5 } + 34.3).map { it.copy(skinTempDevC = it.skinTempC, skinTempC = null) }
        val existing = row("2026-07-06", deviation = -0.42, celsius = 33.7)
        val deviationOnly = row("2026-07-07", deviation = 0.2)
        val ambiguous = legacy.map { it.copy(deviceId = "registered-strap") }
        val computed = legacy.map { it.copy(deviceId = "my-whoop-noop") }
        val noMarker = row("2026-07-08", deviation = 34.2)
        val mismatched = row("2026-07-09", deviation = 34.2)
        val points = legacy.map { MetricSeriesRow("my-whoop", it.day, "skin_temp", it.skinTempDevC!!) } +
            MetricSeriesRow("my-whoop", mismatched.day, "skin_temp", 34.1)
        assertTrue(WhoopCsvImporter.skinTempRepair(ambiguous, points, deviceId = "registered-strap").isEmpty())
        val repaired = WhoopCsvImporter.skinTempRepair(legacy + listOf(existing, deviationOnly, noMarker, mismatched) + ambiguous + computed, points)
        assertEquals(WhoopCsvImporter.withSkinTempDeviations(nights(List(4) { 33.5 } + 34.3)), repaired)
        assertTrue(WhoopCsvImporter.skinTempRepair(repaired + listOf(existing, deviationOnly, noMarker, mismatched), points).isEmpty())
        assertTrue(WhoopCsvImporter.skinTempRepair(legacy, emptyList()).isEmpty())
        assertNull(repaired.first().skinTempDevC)
        assertTrue(repaired.all { it.recovery == 72.0 && it.steps == 4321 && it.sleepHrOnly == true })
    }
}
