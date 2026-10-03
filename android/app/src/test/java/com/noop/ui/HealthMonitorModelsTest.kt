package com.noop.ui

import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.Baselines
import com.noop.analytics.HealthMonitorAssessment
import com.noop.analytics.HealthSignalReliability
import com.noop.data.DailyMetric
import com.noop.data.DemoSeeder
import com.noop.data.MetricSeriesRow
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthMonitorModelsTest {
    @Test fun bankedLocalNightBeforeRolloverMatchesHome() {
        val now = ZonedDateTime.parse("2026-06-20T02:00:00-03:00")
        val prior = DailyMetric("strap", "2026-06-19", avgHrv = 80.0)
        val unbanked = DailyMetric("strap", "2026-06-20", avgHrv = 70.0)
        assertEquals("2026-06-19", healthMonitorDay(listOf(prior, unbanked), now))
        assertEquals("2026-06-20", healthMonitorDay(listOf(prior, unbanked.copy(totalSleepMin = 420.0)), now))
        assertEquals("2026-06-20", healthMonitorDay(listOf(prior, unbanked), now.plusHours(3)))
        assertEquals("2026-06-20", healthMonitorDay(emptyList(), now.plusHours(3)))
    }

    @Test fun nonfiniteAndImplausibleValuesAreNotFormattedAsReadings() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1e300, 500.0)) {
            assertNull(healthMonitorDisplayValue("hrv", value))
        }
        assertEquals(80.0, healthMonitorDisplayValue("hrv", 80.0)!!, 0.0)
    }

    @Test fun missingCurrentDayDoesNotAssessCarriedOrFutureReadings() {
        val days = listOf(
            DailyMetric("strap", "2026-10-01", restingHr = 50),
            DailyMetric("strap", "2026-10-03", restingHr = 50),
        )
        val current = healthMonitorCurrentDay(days, "2026-10-02")
        val result = HealthMonitorAssessment.assess(current?.restingHr?.toDouble(),
            List(14) { 50.0 }, Baselines.restingHRCfg)
        assertNull(current)
        assertEquals(HealthMonitorAssessment.Status.UNAVAILABLE, result.status)
    }

    @Test fun nonfiniteCurrentVitalFieldsAreRemovedBeforeLegacyFormatting() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val original = DailyMetric("strap", "2026-10-02", avgHrv = invalid, respRateBpm = invalid,
                spo2Pct = invalid, skinTempC = invalid, skinTempDevC = invalid, restingHr = 50, recovery = 70.0)
            val current = healthMonitorCurrentDayMetric(listOf(original), original.day)
            assertEquals(original.copy(avgHrv = null, respRateBpm = null, spo2Pct = null,
                skinTempC = null, skinTempDevC = null), current)
            assertEquals(HealthMonitorAssessment.Status.UNAVAILABLE,
                HealthMonitorAssessment.assess(original.avgHrv, List(14) { 60.0 }, Baselines.hrvCfg).status)
        }
    }

    @Test fun historicalNanIsRemovedWithoutChangingTheFiniteCurrentReading() {
        val prior = DailyMetric("strap", "2026-10-01", avgHrv = Double.NaN,
            spo2Pct = Double.POSITIVE_INFINITY, skinTempC = Double.NEGATIVE_INFINITY)
        val current = DailyMetric("strap", "2026-10-02", avgHrv = 60.0, respRateBpm = 14.0,
            spo2Pct = 98.0, skinTempC = 34.2, skinTempDevC = 0.2, recovery = 70.0)
        val displayDays = listOf(prior, current).map(::healthMonitorFiniteMetric)
        assertNull(displayDays.first().avgHrv)
        assertNull(displayDays.first().spo2Pct)
        assertNull(displayDays.first().skinTempC)
        assertEquals(current, healthMonitorCurrentDayMetric(displayDays, current.day))
        assertEquals(Double.NaN, prior.avgHrv!!, 0.0)
    }

    @Test fun demoMarkersUseComputedUnionAndSeparateValidSyntheticSignals() {
        val days = listOf(
            DailyMetric("my-whoop", "2026-10-01", avgHrv = 60.0, respRateBpm = 14.0),
            DailyMetric("my-whoop", "2026-10-02", avgHrv = Double.NaN, respRateBpm = Double.NaN),
        )
        val markers = mutableListOf<MetricSeriesRow>()
        DemoSeeder.seedHealthMonitor(markers, days)
        assertEquals(6, markers.size)
        assertEquals(setOf("my-whoop-noop"), markers.map { it.deviceId }.toSet())
        val valid = markers.filter { it.day == "2026-10-01" }.associate { it.key to it.value }
        assertEquals(60.0, HealthSignalReliability.hrv(60.0, computed = true,
            freshScoringValid = valid["hrv_fresh_scoring_valid"], overcount = valid["hrv_rr_overcount"])!!, 0.0)
        val invalid = markers.first { it.day == "2026-10-02" && it.key == "hrv_fresh_scoring_valid" }
        assertEquals(0.0, invalid.value, 0.0)
        assertEquals(14.0, HealthSignalReliability.respiration(14.0, computed = true,
            freshScoringValid = valid["resp_fresh_scoring_valid"])!!, 0.0)
        assertEquals(0.0, markers.first { it.day == "2026-10-02" && it.key == "resp_fresh_scoring_valid" }.value, 0.0)
        assertEquals(listOf(0.0, 0.0), markers.filter { it.key == "hrv_rr_overcount" }.map { it.value })
    }

    @Test fun baselineExcludesDisplayedDayAndPadsWearGapsThroughYesterday() {
        val rows = listOf("2026-09-27" to 50.0, "2026-09-29" to 54.0,
            "2026-10-02" to 200.0, "2026-10-03" to 210.0, "invalid" to 99.0)
        assertEquals(listOf(50.0, null, 54.0, null, null), healthMonitorHistory(rows, "2026-10-02"))
    }

    @Test fun middayBaselineResetStartsAtTheNextUtcDay() {
        val epoch = LocalDate.parse("2026-09-28").toEpochDay() * 86_400L + 43_200L
        val rows = listOf("2026-09-27" to 50.0, "2026-09-28" to 51.0, "2026-09-29" to 52.0)
        assertEquals(listOf(52.0, null, null), healthMonitorHistory(rows, "2026-10-02", epoch))
    }

    @Test fun reportUsesInclusiveWindowAndNeverCountsMissingAsZero() {
        val days = listOf(
            DailyMetric("strap", "2026-09-02", avgHrv = 10.0),
            DailyMetric("strap", "2026-09-03", avgHrv = 30.0),
            DailyMetric("strap", "2026-09-04", avgHrv = Double.NaN),
            DailyMetric("strap", "2026-10-01", avgHrv = 50.0),
            DailyMetric("strap", "2026-10-02", avgHrv = null),
            DailyMetric("strap", "2026-10-03", avgHrv = 100.0),
        )
        val report = healthMonitorReport(days, LocalDate.parse("2026-10-02"), 30, SkinTempDisplay.Kind.ABSOLUTE, hrvReliabilityByDay = days.mapNotNull { row -> row.avgHrv?.let { row.day to HealthSignalReliability.Record(it, true) } }.toMap())
        val hrv = report.rows.first { it.key == "hrv" }
        assertEquals("2026-09-03", report.start)
        assertEquals(2, hrv.nights)
        assertEquals(40.0, hrv.mean!!, 0.0)
        assertEquals(30.0, hrv.minimum!!, 0.0)
        assertEquals(50.0, hrv.maximum!!, 0.0)
        assertNull(report.rows.first { it.key == "spo2" }.mean)
    }

    @Test fun reportExcludesFlaggedHrvImplausibleOxygenAndMixedTemperatureScales() {
        val days = listOf(
            DailyMetric("strap", "2026-09-29", avgHrv = 80.0, spo2Pct = 110.0, skinTempC = 34.0, skinTempDevC = 0.2),
            DailyMetric("import", "2026-09-30", avgHrv = 40.0, spo2Pct = 98.0, skinTempDevC = 35.0),
            DailyMetric("strap", "2026-10-01", skinTempDevC = -9.0),
        )
        val absolute = healthMonitorReport(days, LocalDate.parse("2026-10-02"), 180, SkinTempDisplay.Kind.ABSOLUTE, mapOf("2026-09-29" to HealthSignalReliability.Record(80.0, false), "2026-09-30" to HealthSignalReliability.Record(40.0, true)))
        assertEquals(40.0, absolute.rows.first { it.key == "hrv" }.mean!!, 0.0)
        assertEquals(98.0, absolute.rows.first { it.key == "spo2" }.mean!!, 0.0)
        assertEquals(34.5, absolute.rows.first { it.key == "skin" }.mean!!, 0.0)
        val deviation = healthMonitorReport(days, LocalDate.parse("2026-10-02"), 180, SkinTempDisplay.Kind.DEVIATION, mapOf("2026-09-29" to HealthSignalReliability.Record(80.0, false), "2026-09-30" to HealthSignalReliability.Record(40.0, true)))
        val skin = deviation.rows.first { it.key == "skin" }
        assertEquals(1, skin.nights)
        assertEquals(0.2, skin.mean!!, 0.0)
    }

    @Test fun rawOpticalChannelsDoNotBecomeReportedBloodOxygen() {
        val days = listOf(DailyMetric("strap", "2026-10-02", spo2Red = 50000, spo2Ir = 60000))
        val report = healthMonitorReport(days, LocalDate.parse("2026-10-02"), 30, SkinTempDisplay.Kind.ABSOLUTE)
        val oxygen = report.rows.first { it.key == "spo2" }
        assertNull(oxygen.mean)
        assertEquals(0, oxygen.nights)
    }

    @Test fun reportRequiresMatchingRespirationEvidenceIndependentlyOfHrv() {
        val day = DailyMetric("strap", "2026-10-01", avgHrv = 60.0, respRateBpm = 14.0)
        for (record in listOf(null, HealthSignalReliability.Record(14.0, false), HealthSignalReliability.Record(15.0, true))) {
            val evidence = record?.let { mapOf(day.day to it) }
            val report = healthMonitorReport(listOf(day), LocalDate.parse("2026-10-02"), 30,
                SkinTempDisplay.Kind.ABSOLUTE, respReliabilityByDay = evidence)
            assertNull(report.rows.first { it.key == "resp" }.mean)
        }
        val trusted = healthMonitorReport(listOf(day), LocalDate.parse("2026-10-02"), 30,
            SkinTempDisplay.Kind.ABSOLUTE, hrvReliabilityByDay = mapOf(day.day to HealthSignalReliability.Record(60.0, false)),
            respReliabilityByDay = mapOf(day.day to HealthSignalReliability.Record(14.0, true)))
        assertEquals(14.0, trusted.rows.first { it.key == "resp" }.mean!!, 0.0)
        assertNull(trusted.rows.first { it.key == "hrv" }.mean)
    }

    @Test fun reportEligibilityCountsUniqueRecordedRecoveriesThroughCurrentDay() {
        val days = listOf(
            DailyMetric("strap", "2026-09-29", recovery = 50.0),
            DailyMetric("import", "2026-09-29", recovery = 60.0),
            DailyMetric("strap", "2026-09-30", recovery = null),
            DailyMetric("strap", "2026-10-01", recovery = Double.NaN),
            DailyMetric("strap", "2026-10-02", recovery = 70.0),
            DailyMetric("strap", "2026-10-03", recovery = 80.0),
        )
        assertEquals(2, healthMonitorRecoveryCount(days, "2026-10-02"))
    }
}
