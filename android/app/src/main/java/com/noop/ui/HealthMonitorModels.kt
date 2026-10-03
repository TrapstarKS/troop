package com.noop.ui

import com.noop.analytics.Baselines
import com.noop.analytics.HealthMonitorAssessment
import com.noop.analytics.MetricCfg
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import java.time.LocalDate

internal val healthMonitorKeys = listOf("hrv", "rhr", "resp", "spo2", "skin")

internal data class HealthMonitorReading(
    val vital: Vital,
    val assessment: HealthMonitorAssessment.Result,
)

internal fun healthMonitorCurrentDay(days: List<DailyMetric>, day: String): DailyMetric? =
    days.lastOrNull { it.day == day }

internal fun healthMonitorCurrentDayMetric(days: List<DailyMetric>, day: String): DailyMetric? =
    healthMonitorCurrentDay(days, day)?.let(::healthMonitorFiniteMetric)

internal fun healthMonitorFiniteMetric(metric: DailyMetric): DailyMetric = metric.copy(
    avgHrv = metric.avgHrv?.takeIf(Double::isFinite),
    respRateBpm = metric.respRateBpm?.takeIf(Double::isFinite),
    spo2Pct = metric.spo2Pct?.takeIf(Double::isFinite),
    skinTempC = metric.skinTempC?.takeIf(Double::isFinite),
    skinTempDevC = metric.skinTempDevC?.takeIf(Double::isFinite),
)

internal fun healthMonitorHistory(
    rows: List<Pair<String, Double?>>,
    day: String,
    baselineEpoch: Long = 0L,
): List<Double?> {
    val end = LocalDate.parse(day).minusDays(1)
    val prior = rows.mapNotNull { (key, value) ->
        runCatching { LocalDate.parse(key) }.getOrNull()
            ?.takeIf { !it.isAfter(end) && (baselineEpoch <= 0L || it.toEpochDay() * 86_400L >= baselineEpoch) }
            ?.let { it.toString() to value?.takeIf(Double::isFinite) }
    }
    if (prior.isEmpty()) return emptyList()
    val padded = if (prior.any { it.first == end.toString() }) prior else prior + (end.toString() to null)
    return VitalBands.calendarSeries(padded)
}

internal fun healthMonitorReadings(
    day: String,
    days: List<DailyMetric>,
    tempUnit: TemperatureUnit,
    hrvOverCountByDay: Map<String, Double>,
    skinTempPreferred: SkinTempDisplay.Kind,
    hrvBaselineEpoch: Long = 0L,
    recoveryBaselineEpoch: Long = 0L,
    hrvReliabilityByDay: Map<String, Boolean>? = null,
): List<HealthMonitorReading> {
    val original = healthMonitorCurrentDay(days, day)
    val current = healthMonitorCurrentDayMetric(days, day)
    val displayDays = days.map(::healthMonitorFiniteMetric)
    val vitals = vitalsFor(current, displayDays, tempUnit, emptyMap(), false,
        hrvOverCountByDay, skinTempPreferred).associateBy { it.key }
    return healthMonitorKeys.map { key ->
        val value = when (key) {
            "hrv" -> original?.avgHrv
            "rhr" -> original?.restingHr?.toDouble()
            "resp" -> original?.respRateBpm
            "spo2" -> original?.spo2Pct
            else -> SkinTempDisplay.leadReading(original?.skinTempC, original?.skinTempDevC, skinTempPreferred)?.value
        }
        val resolved = vitals.getValue(key)
        val rawVital = resolved.copy(value = value,
            secondary = resolved.secondary.takeIf { value?.isFinite() == true })
        val hrvVerified = hrvReliabilityByDay?.get(day) == true
        val vital = if (key == "hrv" && hrvVerified) rawVital.copy(caveat = null) else rawVital
        val skinAbsolute = vital.value?.let(VitalBands::isAbsoluteSkinTemp) ?: true
        val cfg = healthMonitorCfg(key, skinAbsolute)
        val history = healthMonitorHistory(days.map { row ->
            row.day to when (key) {
                "hrv" -> row.avgHrv?.takeIf { hrvReliabilityByDay?.get(row.day) == true }
                "rhr" -> row.restingHr?.toDouble()
                "resp" -> row.respRateBpm
                "spo2" -> row.spo2Pct
                else -> if (skinAbsolute) row.skinTempC ?: row.skinTempDevC?.takeIf(VitalBands::isAbsoluteSkinTemp)
                    else row.skinTempDevC?.takeUnless(VitalBands::isAbsoluteSkinTemp)
            }
        }, day, if (key == "hrv") hrvBaselineEpoch else if (key == "spo2") 0L else recoveryBaselineEpoch)
        HealthMonitorReading(vital, HealthMonitorAssessment.assess(vital.value, history, cfg,
            verified = if (key == "hrv") hrvVerified else !vital.isEstimate && vital.caveat == null))
    }
}

internal data class HealthMonitorReportRow(
    val key: String,
    val mean: Double?,
    val minimum: Double?,
    val maximum: Double?,
    val nights: Int,
)

internal data class HealthMonitorReport(
    val start: String,
    val end: String,
    val rows: List<HealthMonitorReportRow>,
)

internal fun healthMonitorReport(
    days: List<DailyMetric>,
    end: LocalDate,
    windowDays: Int,
    skinKind: SkinTempDisplay.Kind,
    hrvOverCountByDay: Map<String, Double> = emptyMap(),
    hrvReliabilityByDay: Map<String, Boolean>? = null,
): HealthMonitorReport {
    val start = end.minusDays(windowDays.toLong() - 1)
    val window = days.filter { row ->
        runCatching { LocalDate.parse(row.day) }.getOrNull()?.let { it >= start && it <= end } == true
    }.associateBy { it.day }.values
    return HealthMonitorReport(start.toString(), end.toString(), healthMonitorKeys.map { key ->
        val cfg = healthMonitorCfg(key, skinKind == SkinTempDisplay.Kind.ABSOLUTE)
        val values = window.mapNotNull { row ->
            when (key) {
                "hrv" -> row.avgHrv?.takeIf { hrvReliabilityByDay?.get(row.day) == true }
                "rhr" -> row.restingHr?.toDouble()
                "resp" -> row.respRateBpm
                "spo2" -> row.spo2Pct
                else -> when (skinKind) {
                    SkinTempDisplay.Kind.ABSOLUTE -> row.skinTempC ?: row.skinTempDevC?.takeIf(VitalBands::isAbsoluteSkinTemp)
                    SkinTempDisplay.Kind.DEVIATION -> row.skinTempDevC?.takeUnless(VitalBands::isAbsoluteSkinTemp)
                }
            }?.takeIf { it.isFinite() && it >= cfg.minVal && it <= cfg.maxVal }
        }
        HealthMonitorReportRow(key, values.takeIf { it.isNotEmpty() }?.average(), values.minOrNull(),
            values.maxOrNull(), values.size)
    })
}

internal fun healthMonitorRecoveryCount(days: List<DailyMetric>, endDay: String): Int = days.filter {
    it.day <= endDay && runCatching { LocalDate.parse(it.day) }.isSuccess && it.recovery?.isFinite() == true
}.map { it.day }.distinct().size

private fun healthMonitorCfg(key: String, skinAbsolute: Boolean): MetricCfg = when (key) {
    "hrv" -> Baselines.hrvCfg
    "rhr" -> Baselines.restingHRCfg
    "resp" -> Baselines.respCfg
    "spo2" -> HealthMonitorAssessment.bloodOxygenCfg
    else -> if (skinAbsolute) Baselines.metricCfg.getValue("skin_temp") else VitalBands.skinTempDeviationCfg
}
